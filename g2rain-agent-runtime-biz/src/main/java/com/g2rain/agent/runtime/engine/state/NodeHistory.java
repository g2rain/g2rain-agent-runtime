package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.domain.react.ChatRole;
import com.g2rain.agent.runtime.domain.react.ChatTurn;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 节点 ReAct 历史重建：按轮次合并对话、无证据动作与业务证据，生成发给模型的消息序列。
 *
 * @author alpha
 * @since 2026/10/12
 */
final class NodeHistory {

    /**
     * 证据报文与追问参数的 JSON 编解码。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 追问工具的问题参数名。
     */
    private static final String ASK_QUESTION = "question";

    /**
     * 追问工具的待补参数名。
     */
    private static final String ASK_FIELDS = "expectedFields";

    /**
     * 静态构建工具，禁止实例化。
     */
    private NodeHistory() {

    }

    /**
     * 按轮次合并本节点的对话消息、业务证据与无证据动作，重建为 ReAct {@link ChatTurn} 列表。
     *
     * <p>每一轮依次为：用户的话与用户动作（确认、拒绝）→ 模型的文字与工具调用 → 各调用的返回。
     * 业务工具的返回：未成功时取证据上的完整说明，成功时取完整报文，都取不到才退回结果摘要；
     * 内置工具、被拒或推迟的调用取动作上记录的说明。没有轮次的消息排在最前。</p>
     *
     * @param current 当前节点
     * @param tools   本节点可见工具，用于识别追问调用
     * @param body    按 evidenceId 读证据报文；读不到返回 JSON null
     * @return 含 USER / ASSISTANT / TOOL 的跨轮历史
     */
    static List<ChatTurn> build(DefaultNodeScope current, List<ToolCallback> tools, Function<String, JsonNode> body) {
        String nodeId = current.nodeId();
        List<ChatTurn> leadingUser = new ArrayList<>();
        List<String> textsWithoutRound = new ArrayList<>();
        Map<Integer, RoundSlice> rounds = new TreeMap<>();
        current.turn().allDialogue().forEach(message -> {
            // 只投影本节点、有角色的交互历史；会话级消息不进 ReAct
            if (!Objects.equals(message.getNodeId(), nodeId) || Objects.isNull(message.getRole())) {
                return;
            }

            String text = Objects.toString(message.getText(), "");
            Integer round = message.getRound();
            if (Objects.isNull(round)) {
                switch (message.getRole()) {
                    case USER -> leadingUser.add(new ChatTurn(ChatRole.USER, text));
                    case AGENT -> textsWithoutRound.add(text);
                }
                return;
            }

            RoundSlice slice = rounds.computeIfAbsent(round, _ -> new RoundSlice());
            switch (message.getRole()) {
                case USER -> slice.replies.add(text);
                case AGENT -> slice.texts.add(text);
            }
        });
        current.actions().forEach(action -> {
            RoundSlice slice = rounds.computeIfAbsent(action.round(), _ -> new RoundSlice());
            if (action.actor() == DialogueRole.USER) {
                slice.users.add(action.message());
                return;
            }

            slice.calls.add(new RecordedCall(
                new ChatTurn.ToolCallPart(Objects.toString(action.callId(), ""), Objects.toString(action.toolName(), ""),
                    Strings.defaultIfBlank(action.arguments(), "{}")),
                action.message()
            ));
        });
        current.evidence().forEach(head -> rounds.computeIfAbsent(head.round(), _ -> new RoundSlice()).calls.add(
            new RecordedCall(
                new ChatTurn.ToolCallPart(head.evidenceId(), head.toolName(), Strings.defaultIfBlank(head.argsBrief(), "{}")),
                resultOf(head, body)
            )
        ));

        // 追问之后那一轮的用户回复，带上问题与待补参数，免得模型对不上
        ChatTurn.ToolCallPart ask = null;
        for (RoundSlice slice : rounds.values()) {
            if (Objects.nonNull(ask)) {
                ChatTurn.ToolCallPart asked = ask;
                slice.replies.replaceAll(reply -> answered(asked, reply));
            }
            ask = slice.ask(tools);
        }

        List<ChatTurn> turns = new ArrayList<>(leadingUser);
        textsWithoutRound.stream().filter(Strings::isNotBlank)
            .forEach(text -> turns.add(new ChatTurn(ChatRole.ASSISTANT, text)));
        rounds.values().forEach(slice -> slice.emit(turns));
        return turns;
    }

    /**
     * 把对追问的回复写成"回答了哪个问题、对应哪些参数"。
     *
     * @param ask   追问调用
     * @param reply 用户回复原文
     * @return 带上下文的回复
     */
    private static String answered(ChatTurn.ToolCallPart ask, String reply) {
        if (Strings.isBlank(reply)) {
            return reply;
        }

        JsonNode args = RuntimeUtils.argsNode(ask.arguments());
        JsonNode questionNode = args.get(ASK_QUESTION);
        String question = JSON.hasValue(questionNode) ? questionNode.asString() : "";
        List<String> fields = new ArrayList<>();
        JsonNode fieldsNode = args.get(ASK_FIELDS);
        if (Objects.nonNull(fieldsNode) && fieldsNode.isArray()) {
            fieldsNode.forEach(field -> {
                if (JSON.hasValue(field)) {
                    fields.add(field.asString());
                }
            });
        }

        if (fields.isEmpty()) {
            return ModelHint.USER_ANSWERED_PLAIN.text("question", question, "answer", reply);
        }
        return ModelHint.USER_ANSWERED.text("question", question, "fields", String.join(", ", fields), "answer", reply);
    }

    /**
     * 业务证据给模型的返回：未成功取完整说明，成功取完整报文，都没有时退回结果摘要。
     *
     * @param head 证据头
     * @param body 按 evidenceId 读证据报文
     * @return 返回内容
     */
    private static String resultOf(EvidenceHead head, Function<String, JsonNode> body) {
        if (Strings.isNotBlank(head.detail())) {
            return head.detail();
        }

        if (head.status() == EvidenceStatus.SUCCESS) {
            JsonNode payload = body.apply(head.evidenceId());
            if (JSON.hasValue(payload)) {
                return JSON.obj2str(payload);
            }
        }

        return Objects.toString(head.resultBrief(), "");
    }

    /**
     * 一个轮次内的历史片段。
     */
    private static final class RoundSlice {

        /**
         * 用户的话。
         */
        private final List<String> replies = new ArrayList<>();

        /**
         * 用户动作（确认、拒绝等）。
         */
        private final List<String> users = new ArrayList<>();

        /**
         * 模型的文字。
         */
        private final List<String> texts = new ArrayList<>();

        /**
         * 工具调用及其返回。
         */
        private final List<RecordedCall> calls = new ArrayList<>();

        /**
         * 本轮的追问调用。
         *
         * @param tools 本节点可见工具
         * @return 追问调用；没有为 {@code null}
         */
        ChatTurn.@Nullable ToolCallPart ask(List<ToolCallback> tools) {
            return calls.stream().map(RecordedCall::part)
                .filter(part -> ToolCallbacks.is(ToolCallbacks.find(tools, part.name()), BuiltinToolAction.ASK))
                .reduce((_, second) -> second)
                .orElse(null);
        }

        /**
         * 按"用户 → 模型文字与调用 → 调用返回"追加到历史；没有调用时只追加模型文字。
         *
         * @param turns 历史列表
         */
        void emit(List<ChatTurn> turns) {
            replies.stream().filter(Strings::isNotBlank).forEach(text -> turns.add(new ChatTurn(ChatRole.USER, text)));
            users.stream().filter(Strings::isNotBlank).forEach(text -> turns.add(new ChatTurn(ChatRole.USER, text)));
            String content = joinTexts(texts);
            if (calls.isEmpty()) {
                if (Strings.isNotBlank(content)) {
                    turns.add(new ChatTurn(ChatRole.ASSISTANT, content));
                }
                return;
            }

            // 有调用的轮次必须带 TOOL 回填，否则模型看不到调用结果
            turns.add(ChatTurn.assistantWithCalls(content, calls.stream().map(RecordedCall::part).toList()));
            calls.forEach(call -> turns.add(ChatTurn.tool(call.part().id(), call.part().name(), call.result())));
        }
    }

    /**
     * 一次工具调用及其返回。
     *
     * @param part   调用
     * @param result 返回内容
     */
    private record RecordedCall(ChatTurn.ToolCallPart part, String result) {

    }

    /**
     * 把同一轮多段 AGENT 文本用换行拼成一条 assistant 内容。
     *
     * @param texts 同一 round 的 AGENT 文本；可空
     * @return 拼接结果；无有效文本时为空串
     */
    private static String joinTexts(@Nullable List<String> texts) {
        if (Objects.isNull(texts) || texts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String text : texts) {
            if (Strings.isBlank(text)) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(text);
        }
        return sb.toString();
    }
}
