package com.g2rain.agent.runtime.engine.flow.finalize;



import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.progress.StepStage;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.model.ModelGateway;
import com.g2rain.agent.runtime.engine.model.ModelUse;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import com.g2rain.agent.runtime.event.StepEvents;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.prompt.GoalGatePrompt;
import com.g2rain.agent.runtime.support.RequestLocale;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.support.ChatCompletionRequests;
import com.g2rain.model.nexus.support.OpenAiChatClientConverter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * DefaultGoalGate：按节点事实决定直接采用节点结论，还是交模型核验目标并合成答复。
 *
 * <p>直接采用：只有一个主节点（非委派前置），已完成且有结论，并且有规划或契约给出的完成目标（完成判定已保证目标写工具成功）
 * 或所用技能没有写工具。其余情况（多个主节点、技能有写工具却无完成目标、完成目标由首个写调用自动记录）交模型核验；模型判定未达成时，
 * 答复须如实说明已完成与未完成部分，任务状态不变。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class DefaultGoalGate implements GoalGate {

    /**
     * JSON 编解码：组装输入、解析模型输出。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 模型不可用时拼接多个节点结论的分隔符。
     */
    private static final String JOINER = "\n";

    /**
     * 运行时 GoalGate 开关与证据包上限。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 模型网关，核验目标时调用。
     */
    private final ModelGateway modelGateway;

    /**
     * 执行步骤的记录与推送。
     */
    private final StepEvents stepEvents;

    /**
     * 呈现最终答复：未成功退回草稿 → 可直接采用的单节点结论 → 模型核验 → 拼接节点结论。
     *
     * @param ctx       本轮上下文
     * @param draft     执行器带回的文案；可空
     * @param succeeded 任务是否终态成功
     * @return 给用户的答复；未成功且草稿为空时为 {@code null}
     */
    @Override
    public @Nullable String present(TurnContext ctx, @Nullable String draft, boolean succeeded) {
        if (!succeeded) {
            String reply = Strings.blankToNull(draft);
            Trace.at(TraceStage.FINALIZE, "reply").kv("source", ReplySource.NOT_SUCCEEDED).info();
            return reply;
        }

        RuntimeProperties.GoalGate cfg = runtimeProperties.getGoalGate();
        GoalEvidencePack pack = GoalEvidenceAssembler.assemble(ctx, cfg);
        String adopted = adoptable(pack);
        if (Objects.nonNull(adopted)) {
            return traced(adopted, ReplySource.NODE);
        }

        if (cfg.isEnabled()) {
            String stepId = stepEvents.start(StepStage.VERIFY, RuntimeMessage.STEP_VERIFY.text());
            try {
                GoalGateAnswer answer = verify(pack, cfg.getPromptMaxChars(), ctx.getRequest().getModel());
                if (Objects.nonNull(answer)) {
                    ctx.getTurn().task().ifPresent(task -> task.setGoalMet(!Boolean.FALSE.equals(answer.achieved())));
                    if (Boolean.FALSE.equals(answer.achieved())) {
                        Trace.at(TraceStage.FINALIZE, "goal_unmet").kv("goal", pack.goal()).warn();
                        stepEvents.done(stepId, RuntimeMessage.STEP_VERIFY_UNMET.text());
                        return traced(RuntimeUtils.unescapeLiteral(answer.finalMessage()).trim(), ReplySource.UNMET);
                    }
                    stepEvents.done(stepId, RuntimeMessage.STEP_VERIFY_MET.text());
                    return traced(RuntimeUtils.unescapeLiteral(answer.finalMessage()).trim(), ReplySource.VERIFIED);
                }
                stepEvents.done(stepId, RuntimeMessage.STEP_VERIFY_FAILED.text());
            } catch (RuntimeException ex) {
                // 校验失败降级为拼接结果，不丢已完成的工作
                stepEvents.fail(stepId, RuntimeMessage.STEP_VERIFY_FAILED.text(), RuntimeUtils.businessCode(ex), null);
                log.error("failed to present", ex);
                Trace.at(TraceStage.FINALIZE, "verify_failed")
                    .kv("error", ex.getClass().getSimpleName() + ": " + ex.getMessage())
                    .warn();
            }
        }

        return traced(joined(pack), ReplySource.JOINED);
    }

    /**
     * 可直接采用的节点结论：唯一主节点已完成、有结论，且有非自动记录的完成目标或技能没有写工具。
     *
     * @param pack 证据包
     * @return 节点结论；需要模型核验时为 {@code null}
     */
    private static @Nullable String adoptable(GoalEvidencePack pack) {
        List<GoalNodeSummary> primary = pack.nodes().stream().filter(node -> !node.delegated()).toList();
        if (primary.size() != 1) {
            return null;
        }

        GoalNodeSummary node = primary.getFirst();
        boolean done = NodeStatus.DONE.name().equals(node.status()) && Strings.isNotBlank(node.result());
        boolean settled = (Objects.nonNull(node.target()) && !node.claimed()) || !node.writable();
        return done && settled ? node.result() : null;
    }

    /**
     * 模型不可用或无有效输出时：按图顺序拼接主节点结论；都没有则用通用完成文案。
     *
     * @param pack 证据包
     * @return 非空答复
     */
    private static String joined(GoalEvidencePack pack) {
        String text = pack.nodes().stream()
            .filter(node -> !node.delegated())
            .map(GoalNodeSummary::result)
            .filter(Strings::isNotBlank)
            .collect(Collectors.joining(JOINER));
        return Strings.isNotBlank(text) ? text : RuntimeMessage.REPLY_DONE.text();
    }

    /**
     * 输出答复来源轨迹并原样返回答复。
     *
     * @param reply  最终答复
     * @param source 答复来源
     * @return 最终答复
     */
    private static String traced(String reply, ReplySource source) {
        Trace.at(TraceStage.FINALIZE, "reply").kv("source", source).kv("replyChars", reply.length()).info();
        return reply;
    }

    /**
     * 调模型按 GoalGatePrompt 核验目标并合成答复。
     *
     * @param pack     证据包
     * @param maxChars 单段文本截断上限
     * @param model    本轮选用的模型名；可空
     * @return 模型结论；无法解析或答复为空时为 {@code null}
     */
    private @Nullable GoalGateAnswer verify(GoalEvidencePack pack, int maxChars, @Nullable String model) {
        GoalGateView view = GoalGateView.from(pack, maxChars);
        ChatMessage assistant = OpenAiChatClientConverter.firstMessage(modelGateway.chat(ModelUse.GOAL_GATE, model,
            ChatCompletionRequests.of().system(GoalGatePrompt.SYSTEM.formatted(RequestLocale.tag()))
                .user(GoalGatePrompt.USER.formatted(JSON.obj2str(view))).build()
        ));
        if (Objects.isNull(assistant) || Strings.isBlank(assistant.getContent())) {
            return null;
        }

        GoalGateAnswer answer = JSON.str2obj(RuntimeUtils.extractObject(assistant.getContent()), GoalGateAnswer.class);
        return Objects.isNull(answer) || Strings.isBlank(answer.finalMessage()) ? null : answer;
    }

    /**
     * 模型输出。
     *
     * @param achieved     用户目标是否达成；可空按达成处理
     * @param finalMessage 给用户的最终答复
     */
    private record GoalGateAnswer(@Nullable Boolean achieved, String finalMessage) {

    }

    /**
     * GoalGate 输入数据。
     *
     * @param goal         用户目标
     * @param nodes        节点事实
     * @param dialogueTail 最近对话
     */
    private record GoalGateView(String goal, List<NodeView> nodes, List<DialogueView> dialogueTail) {
        static GoalGateView from(GoalEvidencePack pack, int maxChars) {
            List<NodeView> nodes = pack.nodes().stream().map(node -> NodeView.from(node, maxChars)).toList();
            List<DialogueView> tail = pack.dialogueTail().stream().map(turn -> DialogueView.from(turn, maxChars)).toList();
            return new GoalGateView(Objects.requireNonNull(clip(pack.goal(), maxChars)), nodes, tail);
        }
    }

    /**
     * 单个节点事实。
     *
     * @param title     节点标题；可空
     * @param objective 节点目标；可空
     * @param status    节点状态
     * @param target    完成目标；可空
     * @param claimed   完成目标是否由首个写调用自动记录
     * @param writable  技能是否有写工具
     * @param delegated 是否为委派前置节点
     * @param result    节点结论；可空
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record NodeView(@Nullable String title, @Nullable String objective, String status, @Nullable String target,
                            boolean claimed, boolean writable, boolean delegated, @Nullable String result) {

        static NodeView from(GoalNodeSummary node, int maxChars) {
            return new NodeView(node.title(), clip(node.objective(), maxChars), node.status(), node.target(),
                node.claimed(), node.writable(), node.delegated(), clip(node.result(), maxChars));
        }
    }

    /**
     * 对话一条：角色 + 正文。
     *
     * @param role 发言角色；可空
     * @param text 发言正文；可空
     */
    private record DialogueView(@Nullable String role, @Nullable String text) {

        static DialogueView from(DialogueMessage turn, int maxChars) {
            return new DialogueView(Objects.isNull(turn.getRole()) ? null : turn.getRole().name(),
                clip(turn.getText(), maxChars));
        }
    }

    /**
     * 提示用截断。
     *
     * @param text 原文；可空
     * @param max  最大字符数
     * @return 去空白并截断后的文本；空白为 {@code null}
     */
    private static @Nullable String clip(@Nullable String text, int max) {
        return Strings.isBlank(text) ? null : RuntimeUtils.clip(text.trim(), max);
    }

    /**
     * 最终答复的来源。
     */
    private enum ReplySource {

        /**
         * 直接采用唯一主节点的结论
         */
        NODE,

        /**
         * 模型核验达成并合成
         */
        VERIFIED,

        /**
         * 模型判定目标未达成，答复如实说明
         */
        UNMET,

        /**
         * 模型关闭、失败或无有效输出，拼接节点结论
         */
        JOINED,

        /**
         * 本轮未成功收口（失败、挂起或取消），退回草稿
         */
        NOT_SUCCEEDED
    }
}
