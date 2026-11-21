package com.g2rain.agent.runtime.engine.tools.builtin;


import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.HaltReason;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.tools.ToolRejectedException;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.spec.SourceType;
import com.g2rain.agent.runtime.support.RuntimeUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 判定节点为什么停下等用户，并约束停顿期间可用的控流动作。
 *
 * <p>追问的原因按以下顺序判定，模型不能自行声明：
 * <ol>
 *   <li>有前置条件拒绝待用户决定 → {@link HaltReason#DENIED}，不校验待补参数；</li>
 *   <li>带待补参数：每个都须是允许来自用户的参数，或须来自查询结果且本节点已查到候选；
 *       最近一次业务调用返回多条候选时为 {@link HaltReason#AMBIGUOUS}，否则为 {@link HaltReason#MISSING_INPUT}；</li>
 *   <li>不带待补参数：最近一次业务调用返回多条候选 → {@link HaltReason#AMBIGUOUS}，
 *       结果为空或失败 → {@link HaltReason#UNRESOLVED}，否则没有依据而拒绝。</li>
 * </ol>
 * 没有契约的技能不校验待补参数，也不要求依据。</p>
 *
 * <p>停顿期间的约束：有补查待完成时不能追问、失败或完成；有拒绝待用户决定时不能失败。</p>
 *
 * @author alpha
 * @since 2026/11/21
 */
final class HaltResolver {

    private HaltResolver() {

    }

    /**
     * 判定追问的停顿原因；没有依据或待补参数无效时拒绝。
     *
     * @param ctx            节点执行现场
     * @param expectedFields 模型给出的待补参数名
     * @return 停顿原因
     */
    static HaltReason followUp(ToolExecutionContext ctx, List<String> expectedFields) {
        if (Objects.nonNull(ctx.scope().note(NodeNoteKey.GUARD_DENIAL))) {
            return HaltReason.DENIED;
        }

        SkillContract contract = ctx.contract();
        boolean ambiguous = lastCallAmbiguous(ctx);
        if (expectedFields.isEmpty()) {
            if (ambiguous) {
                return HaltReason.AMBIGUOUS;
            }

            if (lastCallUnresolved(ctx) || Objects.isNull(contract)) {
                return HaltReason.UNRESOLVED;
            }

            throw new ToolRejectedException(RuntimeErrorCode.FOLLOW_UP_UNGROUNDED, ModelHint.NEXT_FOLLOW_UP.text(),
                ModelHint.FOLLOW_UP_NO_BASIS.text());
        }

        if (Objects.nonNull(contract)) {
            boolean hasCandidates = ctx.scope().evidence().stream()
                .anyMatch(head -> head.done() && !Boolean.TRUE.equals(head.empty()));
            List<String> invalid = expectedFields.stream()
                .filter(field -> !askable(contract, field, hasCandidates))
                .toList();
            if (!invalid.isEmpty()) {
                throw new ToolRejectedException(RuntimeErrorCode.FOLLOW_UP_UNGROUNDED, ModelHint.NEXT_FOLLOW_UP.text(),
                    ModelHint.FOLLOW_UP_FIELDS_INVALID.text("fields", String.join(", ", invalid)));
            }
        }

        return ambiguous ? HaltReason.AMBIGUOUS : HaltReason.MISSING_INPUT;
    }

    /**
     * 有补查待完成时拒绝：须先按补查提示查询，再重新调用被拦下的工具。
     *
     * @param ctx 节点执行现场
     */
    static void requireNoLookup(ToolExecutionContext ctx) {
        Object tool = ctx.scope().note(NodeNoteKey.GUARD_LOOKUP);
        if (Objects.nonNull(tool)) {
            String toolName = tool.toString();
            throw new ToolRejectedException(RuntimeErrorCode.LOOKUP_PENDING,
                ModelHint.NEXT_LOOKUP.text("tool", toolName), toolName);
        }
    }

    /**
     * 有拒绝待用户决定时拒绝：须追问用户，由用户决定如何处理。
     *
     * @param ctx 节点执行现场
     */
    static void requireNoDecision(ToolExecutionContext ctx) {
        Object tool = ctx.scope().note(NodeNoteKey.GUARD_DENIAL);
        if (Objects.nonNull(tool)) {
            throw new ToolRejectedException(RuntimeErrorCode.DECISION_PENDING, ModelHint.NEXT_DECISION.text(),
                tool.toString());
        }
    }

    /**
     * 参数能否向用户追问（按完整路径或叶子名匹配本技能工具参数）：允许来自用户，或须来自查询结果且已有候选可选。
     *
     * @param contract      技能契约
     * @param field         待补参数名
     * @param hasCandidates 本节点已有非空的成功查询
     * @return 可以追问
     */
    private static boolean askable(SkillContract contract, String field, boolean hasCandidates) {
        String leaf = RuntimeUtils.leafKey(field);
        return contract.getTools().values().stream()
            .flatMap(tool -> tool.getArgs().entrySet().stream())
            .filter(arg -> arg.getKey().equals(field) || RuntimeUtils.leafKey(arg.getKey()).equals(leaf))
            .map(Map.Entry::getValue)
            .anyMatch(arg -> arg.allows(SourceType.USER) || (hasCandidates && arg.allows(SourceType.EVIDENCE)));
    }

    /**
     * 本节点最近一次业务调用结果为空、业务失败或超时，需要用户澄清。
     *
     * @param ctx 节点执行现场
     * @return 结果为空或失败
     */
    private static boolean lastCallUnresolved(ToolExecutionContext ctx) {
        List<EvidenceHead> evidence = ctx.scope().evidence();
        if (evidence.isEmpty()) {
            return false;
        }

        EvidenceHead last = evidence.getLast();
        boolean failed = last.status() == EvidenceStatus.FAILED || last.status() == EvidenceStatus.TIMEOUT
            || Boolean.FALSE.equals(last.businessOk());
        return failed || (last.done() && Boolean.TRUE.equals(last.empty()));
    }

    /**
     * 本节点最近一次业务调用成功且有抽取项返回多条，需要用户从候选中选择。
     *
     * @param ctx 节点执行现场
     * @return 有多条候选
     */
    private static boolean lastCallAmbiguous(ToolExecutionContext ctx) {
        List<EvidenceHead> evidence = ctx.scope().evidence();
        if (evidence.isEmpty()) {
            return false;
        }

        EvidenceHead last = evidence.getLast();
        return last.done() && Objects.nonNull(last.extractCounts())
            && last.extractCounts().values().stream().anyMatch(count -> count > 1);
    }
}
