package com.g2rain.agent.runtime.engine.flow.finalize;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 从本轮上下文拼装 GoalGate 证据包，并提供「够长正文」采纳。
 *
 * <p>当前组装写入目标摘要、节点摘要与交互历史尾；观察摘录与汇总结论字段保留给调用方补填。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class GoalEvidenceAssembler {

    /**
     * 汇总结论里任务前情综合项的键名，采纳长正文时跳过该项。
     */
    private static final String CONTINUITY_SYNTH_KEY = "continuity";

    /**
     * 禁止实例化：只提供静态组装。
     */
    private GoalEvidenceAssembler() {

    }

    /**
     * 从 TurnContext 组装证据包。
     *
     * @param ctx 本轮上下文
     * @param cfg GoalGate 条数上限
     * @return 证据包；节点摘要与对话尾受配置截断
     */
    public static GoalEvidencePack assemble(TurnContext ctx, RuntimeProperties.GoalGate cfg) {
        GoalEvidencePack pack = new GoalEvidencePack();
        var turn = ctx.getTurn();
        TurnRequest request = ctx.getRequest();

        String goal = turn.task().map(TaskMetadata::getGoalSummary).orElse(null);
        String userInput = request.getUserInput();
        pack.setGoalSummary(Strings.isNotBlank(goal) ? goal.trim() : userInput);
        pack.setUserInput(userInput);

        pack.setNodeSummaries(buildNodeSummaries(turn.graph(), cfg.getNodeSummariesMax()));
        pack.setDialogueTail(turn.dialogue(cfg.getDialogueTail()));
        return pack;
    }

    /**
     * 按图节点顺序摘摘要，条数不超过 {@code max}。
     *
     * @param graph 当前任务图
     * @param max   摘要条数上限
     * @return 节点摘要列表；无节点时为空列表
     */
    private static List<GoalNodeSummary> buildNodeSummaries(TaskGraph graph, int max) {
        List<GoalNodeSummary> out = new ArrayList<>();
        if (Collections.isEmpty(graph.getNodes())) {
            return out;
        }
        for (TaskNode node : graph.getNodes()) {
            if (Strings.isBlank(node.getNodeId()) || out.size() >= max) {
                continue;
            }
            out.add(new GoalNodeSummary(
                node.getNodeId(),
                node.getSkillKey(),
                node.getStatus().name(),
                null,
                null
            ));
        }
        return out;
    }

    /**
     * 从证据包里找已够长的正文：节点产出 → 合成摘要 → 证据摘要。
     *
     * @param pack     证据包；可空
     * @param minChars 最短字数
     * @return 可直接采纳的文案；可空
     */
    public static @Nullable String adoptLongMessage(@Nullable GoalEvidencePack pack, int minChars) {
        if (Objects.isNull(pack)) {
            return null;
        }
        int min = Math.max(1, minChars);
        for (GoalNodeSummary node : pack.nodeSummaries()) {
            if (Strings.isNotBlank(node.finalMessage()) && node.finalMessage().trim().length() >= min) {
                return node.finalMessage().trim();
            }
        }
        String fromSynth = fromSynthesis(pack.joinSynthesis());
        if (Strings.isNotBlank(fromSynth) && fromSynth.length() >= min) {
            return fromSynth;
        }
        for (GoalNodeSummary node : pack.nodeSummaries()) {
            if (Strings.isNotBlank(node.resultSummary()) && node.resultSummary().trim().length() >= min) {
                return node.resultSummary().trim();
            }
        }
        return null;
    }

    /**
     * 合成图里非任务前情综合项的最后一段可读摘要。
     *
     * @param synth 汇总结论表
     * @return 最后一段可读摘要；没有可用项则为 {@code null}
     */
    private static @Nullable String fromSynthesis(Map<String, Object> synth) {
        if (synth.isEmpty()) {
            return null;
        }
        String last = null;
        for (Map.Entry<String, Object> entry : synth.entrySet()) {
            // 任务前情综合项不当作用户可见终稿
            if (CONTINUITY_SYNTH_KEY.equals(entry.getKey())) {
                continue;
            }

            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                Object summary = map.get("summary");
                if (Objects.nonNull(summary) && Strings.isNotBlank(String.valueOf(summary))) {
                    last = String.valueOf(summary).trim();
                }
            } else if (Strings.isNotBlank(String.valueOf(value))) {
                last = String.valueOf(value).trim();
            }
        }
        return last;
    }
}

