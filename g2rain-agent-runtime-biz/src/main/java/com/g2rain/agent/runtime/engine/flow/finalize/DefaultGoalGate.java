package com.g2rain.agent.runtime.engine.flow.finalize;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.engine.model.ModelGateway;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.prompt.GoalGatePrompt;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.support.ChatCompletionRequests;
import com.g2rain.model.nexus.support.OpenAiChatClientConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * DefaultGoalGate：默认目标门——够长则直接采纳，否则可选模型润色。
 * <p>
 * 失败或未开启门时，原样返回确定性草稿；成功时优先长证据 / 长草稿，再视配置调 LLM。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class DefaultGoalGate implements GoalGate {

    /**
     * JSON 编解码：解析模型润色返回。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 运行时 GoalGate 开关与字数门槛。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 模型网关，仅在开启 LLM 润色时调用。
     */
    private final ModelGateway modelGateway;

    /**
     * 呈现最终答复：短路失败 → 采纳长证据 → 够长草稿 → 可选 LLM。
     *
     * @param deterministic 确定性草稿；可空
     * @param evidence      证据包；可空
     * @param succeeded     是否终态成功
     * @return 给人看的答复；失败或未开启时原样返回草稿
     */
    @Override
    public String present(@Nullable String deterministic, @Nullable GoalEvidencePack evidence, boolean succeeded) {
        RuntimeProperties.GoalGate cfg = runtimeProperties.getGoalGate();
        FinalMessagePicker.Pick pick = FinalMessagePicker.pick(deterministic);
        String text = pick.text();
        int minChars = cfg.getMinDeterministicChars();

        // 失败或未开启：不走证据采纳与润色，只保证有可读草稿
        if (!succeeded || !cfg.isEnabled()) {
            return Strings.isNotBlank(deterministic) ? deterministic.trim() : text;
        }

        String adopted = GoalEvidenceAssembler.adoptLongMessage(evidence, minChars);
        if (Strings.isNotBlank(adopted)) {
            return adopted;
        }

        // 确定性草稿或拣选结果已够长且非兜底
        if (Strings.isNotBlank(deterministic) && deterministic.trim().length() >= minChars) {
            return deterministic.trim();
        }
        if (text.length() >= minChars && !"FALLBACK".equals(pick.source())) {
            return text;
        }

        // 未开 LLM：退回确定性
        if (!cfg.isLlmEnabled()) {
            return Strings.isNotBlank(deterministic) ? deterministic.trim() : text;
        }

        try {
            String polished = polish(text, evidence);
            if (Strings.isNotBlank(polished)) {
                return polished.trim();
            }
        } catch (RuntimeException ex) {
            log.warn("goal-gate llm failed: {}", ex.getMessage());
        }
        return Strings.isNotBlank(deterministic) ? deterministic.trim() : text;
    }

    /**
     * 调模型按 GoalGatePrompt 润色，解析 finalMessage。
     *
     * <p>user JSON 字段用途见 {@link GoalGateUserView}。</p>
     *
     * @param deterministic 草稿
     * @param evidence      证据包；可空
     * @return 模型给出的 finalMessage；无法解析则为 {@code null}
     */
    private @Nullable String polish(String deterministic, @Nullable GoalEvidencePack evidence) {
        String goal = Objects.nonNull(evidence) ? Objects.toString(evidence.goalSummary(), "") : "";
        var view = new GoalGateUserView(goal, deterministic,
            EvidenceView.from(evidence, runtimeProperties.getGoalGate().getPromptMaxChars()));
        ChatMessage assistant = OpenAiChatClientConverter.firstMessage(modelGateway.chat(
            ChatCompletionRequests.of().system(GoalGatePrompt.TEXT).user(JSON.obj2str(view)).build()
        ));

        if (Objects.isNull(assistant) || Strings.isBlank(assistant.getContent())) {
            return null;
        }

        // 抠 JSON → 反序列化为 Answer record
        GoalGateAnswer answer = JSON.str2obj(RuntimeUtils.extractObject(assistant.getContent()), GoalGateAnswer.class);
        if (Objects.isNull(answer) || Strings.isBlank(answer.finalMessage())) {
            return null;
        }

        return answer.finalMessage();
    }

    /**
     * 模型输出：finalMessage + 可选 reason。
     *
     * @param finalMessage 润色后的最终答复；可空
     * @param reason       模型自述原因；可空
     */
    private record GoalGateAnswer(@Nullable String finalMessage, @Nullable String reason) {

    }

    /**
     * GoalGate user JSON。
     *
     * <pre>
     * goalSummary   — 用户目标：最终答复必须对准它
     * deterministic — Runtime 确定性草稿：证据不足时的候选底稿
     * evidence      — 本轮执行证据：见 {@link EvidenceView}；可空
     * </pre>
     *
     * @param goalSummary   用户目标；可空
     * @param deterministic Runtime 确定性草稿；可空
     * @param evidence      本轮执行证据；可空
     */
    private record GoalGateUserView(@Nullable String goalSummary, @Nullable String deterministic,
                                    @Nullable EvidenceView evidence) {

    }

    /**
     * user.evidence。
     *
     * <pre>
     * evidenceDigest       — 结果摘要：快速把握本轮结果
     * observationSnippets  — 有效正文片段：答复可用事实句
     * joinSynthesis        — 多结果汇总：多节点合并说法
     * nodeSummaries        — 节点结果：优先复用节点 finalMessage
     * dialogueTail         — 对话尾：指代与接续，不另造事实
     * </pre>
     *
     * @param evidenceDigest      结果摘要；可空
     * @param observationSnippets 有效正文片段；可空
     * @param joinSynthesis       多结果汇总；可空
     * @param nodeSummaries       节点结果
     * @param dialogueTail        交互历史尾；可空
     */
    private record EvidenceView(@Nullable String evidenceDigest, @Nullable List<String> observationSnippets,
                                @Nullable Map<String, Object> joinSynthesis, List<GoalNodeSummary> nodeSummaries,
                                @Nullable List<DialogueTurnView> dialogueTail) {

        static @Nullable EvidenceView from(@Nullable GoalEvidencePack pack, int promptMaxChars) {
            if (Objects.isNull(pack)) {
                return null;
            }

            List<GoalNodeSummary> nodes = new ArrayList<>();
            for (GoalNodeSummary node : pack.nodeSummaries()) {
                // 长字段截断后再入 View
                nodes.add(new GoalNodeSummary(
                    node.nodeId(), node.skillKey(), node.status(),
                    truncateForPrompt(node.resultSummary(), promptMaxChars),
                    truncateForPrompt(node.finalMessage(), promptMaxChars)
                ));
            }

            List<DialogueTurnView> dial = null;
            if (!pack.dialogueTail().isEmpty()) {
                dial = new ArrayList<>();
                for (DialogueMessage turn : pack.dialogueTail()) {
                    dial.add(DialogueTurnView.from(turn, promptMaxChars));
                }
            }

            return new EvidenceView(
                pack.evidenceDigest(),
                pack.observationSnippets().isEmpty() ? null : List.copyOf(pack.observationSnippets()),
                pack.joinSynthesis().isEmpty() ? null : pack.joinSynthesis(),
                List.copyOf(nodes),
                Objects.isNull(dial) ? null : List.copyOf(dial)
            );
        }
    }

    /**
     * 对话尾一条：role + text。
     *
     * @param role 发言角色；可空
     * @param text 发言正文；可空
     */
    private record DialogueTurnView(@Nullable String role, @Nullable String text) {
        static DialogueTurnView from(DialogueMessage turn, int promptMaxChars) {
            return new DialogueTurnView(
                Objects.isNull(turn.getRole()) ? null : turn.getRole().name(),
                truncateForPrompt(turn.getText(), promptMaxChars)
            );
        }
    }

    /**
     * 提示用截断，避免塞爆上下文。
     *
     * @param text 原文；可空或空白时按空串返回
     * @param max  最大字符数
     * @return 截断后的文本；空白输入为空串
     */
    private static String truncateForPrompt(@Nullable String text, int max) {
        if (Strings.isBlank(text)) {
            return "";
        }

        String trimmed = text.trim();
        if (trimmed.length() > max) {
            return trimmed.substring(0, max);
        }
        return trimmed;
    }
}
