package com.g2rain.agent.runtime.engine.flow.finalize;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.common.utils.Strings;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 从本轮上下文拼装 GoalGate 证据包：用户目标、任务图各节点事实与最近对话。
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class GoalEvidenceAssembler {

    /**
     * 禁止实例化：只提供静态组装。
     */
    private GoalEvidenceAssembler() {

    }

    /**
     * 从本轮上下文组装证据包。
     *
     * @param ctx        本轮上下文
     * @param cfg        GoalGate 条数上限
     * @param evidenceOf 按节点 id 取证据头（含跨轮读回）
     * @return 证据包；节点、调用摘要与对话尾受配置截断
     */
    public static GoalEvidencePack assemble(TurnContext ctx, RuntimeProperties.GoalGate cfg,
                                            Function<String, List<EvidenceHead>> evidenceOf) {
        TurnScope turn = ctx.getTurn();
        String goal = turn.task().map(TaskMetadata::getGoalSummary).filter(Strings::isNotBlank)
            .orElseGet(() -> Objects.toString(ctx.getRequest().getUserInput(), ""));

        Set<String> writable = Objects.requireNonNullElse(ctx.getSkills(), List.<Skill>of()).stream()
            .filter(skill -> !skill.writeTools().isEmpty())
            .map(Skill::skillKey)
            .collect(Collectors.toSet());

        List<TaskNode> graphNodes = Objects.requireNonNullElse(turn.graph().getNodes(), List.of());
        List<GoalNodeSummary> nodes = graphNodes.stream()
            .filter(node -> Strings.isNotBlank(node.getNodeId()))
            .limit(Math.max(1, cfg.getNodeSummariesMax()))
            .map(node -> summaryOf(node, writable, calls(evidenceOf.apply(node.getNodeId()), cfg.getEvidencePerNodeMax())))
            .toList();
        return new GoalEvidencePack(goal.trim(), nodes, turn.dialogue(cfg.getDialogueTail()));
    }

    /**
     * 节点最近的成功调用摘要：只取成功且有结果摘要的证据，按时间取末尾若干条。
     *
     * @param heads 节点证据头，时间正序
     * @param max   条数上限
     * @return 调用摘要
     */
    private static List<GoalNodeSummary.Call> calls(List<EvidenceHead> heads, int max) {
        List<GoalNodeSummary.Call> calls = heads.stream()
            .filter(head -> head.status() == EvidenceStatus.SUCCESS && Strings.isNotBlank(head.resultBrief()))
            .map(head -> new GoalNodeSummary.Call(head.toolName(), Objects.requireNonNull(head.resultBrief())))
            .toList();
        int from = Math.max(0, calls.size() - Math.max(0, max));
        return calls.subList(from, calls.size());
    }

    /**
     * 单个节点的事实行。
     *
     * @param node     图节点
     * @param writable 有写工具的技能键
     * @param calls    节点最近的成功调用摘要
     * @return 节点事实
     */
    private static GoalNodeSummary summaryOf(TaskNode node, Set<String> writable, List<GoalNodeSummary.Call> calls) {
        return new GoalNodeSummary(
            node.getNodeId(),
            Strings.blankToNull(node.getTitle()),
            Strings.blankToNull(node.getObjective()),
            node.getStatus().name(),
            Strings.blankToNull(node.getTarget()),
            node.isTargetClaimed(),
            Strings.isNotBlank(node.getSkillKey()) && writable.contains(node.getSkillKey()),
            node.getDelegateDepth() > 0,
            Strings.blankToNull(node.getResult()),
            calls
        );
    }
}
