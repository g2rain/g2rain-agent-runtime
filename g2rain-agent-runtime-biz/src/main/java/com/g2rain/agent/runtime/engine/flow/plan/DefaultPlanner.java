package com.g2rain.agent.runtime.engine.flow.plan;


import lombok.extern.slf4j.Slf4j;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.ExecutionMode;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.plan.PlanStep;
import com.g2rain.agent.runtime.domain.state.progress.StepStage;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;
import com.g2rain.agent.runtime.engine.TurnResult;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.agent.runtime.event.StepEvents;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 默认规划器：按决策生成任务执行图并经 {@code turn.plan} 落图。
 *
 * <p>沿用已有任务图或不必建图时直接返回。否则筛选候选技能，单节点 REACT 走快路径，
 * 否则模型出图；模型失败则退回候选串行图，再补 provider 前置节点。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultPlanner implements Planner {

    /**
     * 技能候选筛选。
     */
    private final SkillSelector skillSelector;

    /**
     * 模型出图。
     */
    private final PlanDrafter planDrafter;

    /**
     * 执行步骤的记录与推送。
     */
    private final StepEvents stepEvents;

    /**
     * 规划本轮任务图并经 Facade 写入当前 Task。
     *
     * <ol>
     *   <li>无 Decision → return
     *   <li>{@code loadExistingPlan}（续跑沿用已有图）→ return
     *   <li>非 {@code createPlan}（不必建图）→ return
     *   <li>校验 Task + {@code ctx.skills}；缺则 failed
     *   <li>取 goal（metadata.goalSummary，否则用户原文）
     *   <li>skills → 短目录；{@link SkillSelector} 筛本轮候选；空则 failed
     *   <li>单候选且契约 {@code executionMode=REACT} → 单节点简图（跳过 LLM 出图）
     *   <li>否则 {@link PlanDrafter} 模型出图；失败 → 候选串行保底
     *   <li>步骤仍空 → failed
     *   <li>{@code PlanGraphs.materialize}（依赖边默认 NORMAL）→ {@code turn.plan} / {@code turn.focus}
     * </ol>
     *
     * @param ctx 本轮上下文
     */
    @Override
    public void plan(TurnContext ctx) {
        TurnDecision decision = ctx.getDecision();
        // 无决策：无从判断是否建图，不把空决策当失败
        if (Objects.isNull(decision)) {
            Trace.at(TraceStage.PLAN, "skipped").kv("reason", SkipReason.NO_DECISION).info();
            return;
        }

        // 续跑沿用热态已有图，不再出新计划
        if (decision.loadExistingPlan()) {
            Trace.at(TraceStage.PLAN, "skipped").kv("reason", SkipReason.LOAD_EXISTING).info();
            return;
        }

        // 仅回复等模式明确不建图
        if (!decision.createPlan()) {
            Trace.at(TraceStage.PLAN, "skipped").kv("reason", SkipReason.NO_PLAN).kv("mode", decision.mode()).info();
            return;
        }

        String stepId = stepEvents.start(StepStage.PLAN, RuntimeMessage.STEP_PLAN.text());
        create(ctx);
        if (ctx.isFailed()) {
            stepEvents.fail(stepId, Objects.isNull(ctx.getResult()) ? null : ctx.getResult().getContent(), null, null);
            return;
        }
        stepEvents.done(stepId, planDetail(ctx.getTurn().graph()));
    }

    /**
     * 新建计划：筛候选、出图、补前置节点并落图；失败时经 {@link #failPlan} 标记。
     *
     * @param ctx 本轮上下文
     */
    private void create(TurnContext ctx) {
        // 新建计划必须已有任务和技能；缺任一则失败，避免画出空图
        var turn = ctx.getTurn();
        TaskMetadata task = turn.task().orElse(null);
        List<Skill> skills = ctx.getSkills();
        if (Objects.isNull(task) || Strings.isBlank(task.getTaskId()) || Collections.isEmpty(skills)) {
            Trace.at(TraceStage.PLAN, "failed")
                .kv("reason", FailReason.MISSING_TASK_OR_SKILLS)
                .kv("taskPresent", Objects.nonNull(task))
                .kv("skillCount", Objects.nonNull(skills) ? skills.size() : 0)
                .error();
            failPlan(ctx, RuntimeMessage.PLAN_FAILED.text());
            return;
        }

        // ⑤ skills → 短目录；筛本轮候选
        Map<String, Skill> skillByKey = skills.stream().collect(Collectors.toMap(
            Skill::skillKey, Function.identity(), (a, _) -> a, LinkedHashMap::new
        ));

        List<SkillItem> allSkills = skills.stream().map(SkillItem::from).toList();
        if (Collections.isEmpty(allSkills)) {
            Trace.at(TraceStage.PLAN, "failed").kv("reason", FailReason.EMPTY_CATALOG).error();
            failPlan(ctx, RuntimeMessage.PLAN_FAILED.text());
            return;
        }

        String goal = goalOf(task, ctx);
        List<SkillItem> candidates = skillSelector.select(ctx, allSkills, goal);
        // 筛不出候选宁可失败，也不把全量技能硬画进图
        if (Collections.isEmpty(candidates)) {
            Trace.at(TraceStage.PLAN, "failed").kv("reason", FailReason.NO_CANDIDATE).kv("skillCount", allSkills.size()).error();
            failPlan(ctx, RuntimeMessage.PLAN_FAILED.text());
            return;
        }

        List<String> candidateKeys = candidates.stream().map(SkillItem::skillKey).toList();
        List<PlanStep> steps;
        PlanSource source;
        // 单候选且契约就是节点内循环：跳过 LLM 出图，避免为一步任务再调一次模型
        if (candidates.size() == 1 && isReactMode(skillByKey.get(candidates.getFirst().skillKey()))) {
            steps = List.of(singleReactStep(candidates.getFirst(), skillByKey, goal));
            source = PlanSource.SINGLE_REACT;
        } else {
            // LLM 空步骤用候选串行保底，仍保证有图可执行
            steps = planDrafter.draft(ctx, candidates, skillByKey, goal);
            if (Collections.isEmpty(steps)) {
                steps = degradeFromCandidates(candidates, skillByKey, goal);
                source = PlanSource.DEGRADED;
            } else {
                source = PlanSource.LLM;
            }
        }

        // 保底后仍空：标失败，不把空图写进任务记忆
        if (Collections.isEmpty(steps)) {
            Trace.at(TraceStage.PLAN, "failed").kv("reason", FailReason.EMPTY_STEPS).kv("candidates", candidateKeys).error();
            failPlan(ctx, RuntimeMessage.PLAN_FAILED.text());
            return;
        }

        // ⑩ 落图、补 provider 前置、focus；空 goal 才补
        TaskGraph graph = PlanGraphs.materialize(steps);
        try {
            ProviderNodes.expand(graph, skillByKey, turn);
            applyDefaultTargets(graph, skillByKey);
        } catch (BusinessException ex) {
            log.error("failed to plan", ex);
            Trace.at(TraceStage.PLAN, "failed").kv("reason", FailReason.PROVIDER).kv("errorCode", ex.getErrorCode()).error();
            failPlan(ctx, RuntimeMessage.PLAN_FAILED.text());
            return;
        }
        String focusNodeId = PlanGraphs.firstReadyNodeId(graph);
        turn.plan(graph);
        if (Strings.isNotBlank(focusNodeId)) {
            turn.focus(focusNodeId);
        }
        // 开任务时未写下 goal 才用本轮规划目标回填，避免覆盖意图摘要
        if (Strings.isBlank(task.getGoalSummary()) && Strings.isNotBlank(goal)) {
            task.setGoalSummary(goal.trim());
        }

        Trace.at(TraceStage.PLAN, "planned")
            .kv("source", source)
            .kv("candidates", candidateKeys)
            .kv("nodeCount", graph.getNodes().size())
            .kv("skills", graph.getNodes().stream().map(TaskNode::getSkillKey).toList())
            .kv("firstReady", focusNodeId)
            .info();
    }

    /**
     * 规划步骤的结论，如"规划 2 个节点：查询订单 → 发起退款"。
     *
     * @param graph 已落图的任务图
     * @return 步骤说明
     */
    private static String planDetail(TaskGraph graph) {
        List<String> titles = graph.getNodes().stream()
            .map(node -> Strings.defaultIfBlank(node.getTitle(), node.getSkillKey()))
            .toList();
        String count = RuntimeMessage.STEP_PLAN_DONE.text("count", String.valueOf(titles.size()));
        return titles.isEmpty() ? count : count + "：" + String.join(" → ", titles);
    }

    /**
     * 规划失败：打失败标记并补 TurnResult。
     *
     * @param ctx     本轮
     * @param message 失败文案
     */
    private static void failPlan(TurnContext ctx, String message) {
        ctx.getTurn().close(TaskStatus.FAILED, message);
        ctx.setFailed(true);
        if (Objects.nonNull(ctx.getResult())) {
            return;
        }
        ctx.setResult(TurnResult.fail(
            ctx.getTurn().conversation().conversationId(),
            ctx.getTurn().task().map(TaskMetadata::getTaskId).orElse(null),
            message
        ));
    }

    /**
     * 规划未给 target 的节点采用技能契约的默认完成目标。
     *
     * @param graph      已落图的任务图
     * @param skillByKey 按 skillKey 查找完整技能
     */
    private static void applyDefaultTargets(TaskGraph graph, Map<String, Skill> skillByKey) {
        for (TaskNode node : graph.getNodes()) {
            if (Strings.isNotBlank(node.getTarget()) || Strings.isBlank(node.getSkillKey())) {
                continue;
            }

            Skill skill = skillByKey.get(node.getSkillKey());
            if (Objects.nonNull(skill)) {
                node.setTarget(skill.target());
            }
        }
    }

    /**
     * 模型失败时的保底计划：候选技能按顺序串成一条依赖链（边落图时 NORMAL）。
     *
     * @param candidates 候选技能
     * @param skillByKey 技能键到技能的映射，用于取步骤标题
     * @param goal       规划目标；可空时用默认步骤标题
     * @return 保底步骤；无候选时为空列表
     */
    private static List<PlanStep> degradeFromCandidates(
        List<SkillItem> candidates, Map<String, Skill> skillByKey, @Nullable String goal) {
        if (Collections.isEmpty(candidates)) {
            return List.of();
        }

        String objective = Strings.isNotBlank(goal) ? goal.trim() : RuntimeMessage.STEP_TITLE_DEFAULT.text();
        return IntStream.range(0, candidates.size()).mapToObj(i -> {
            SkillItem skill = candidates.get(i);
            String nodeId = "n" + (i + 1);
            List<String> dependsOn = i == 0 ? List.of() : List.of("n" + i);
            return new PlanStep(nodeId, titleOf(skill, skillByKey), objective, skill.skillKey(), dependsOn, null);
        }).toList();
    }

    /**
     * 单候选 REACT：一个无依赖节点。
     *
     * @param skill      唯一候选技能
     * @param skillByKey 技能键到技能的映射，用于取步骤标题
     * @param goal       规划目标；可空时用默认步骤标题
     * @return 单节点计划步骤
     */
    private static PlanStep singleReactStep(SkillItem skill, Map<String, Skill> skillByKey, @Nullable String goal) {
        String objective = Strings.isNotBlank(goal) ? goal.trim() : RuntimeMessage.STEP_TITLE_DEFAULT.text();
        return new PlanStep("n1", titleOf(skill, skillByKey), objective, skill.skillKey(), List.of(), null);
    }

    /**
     * 步骤标题：用技能名称，技能缺失时退回技能键。
     *
     * @param skill      候选技能
     * @param skillByKey 技能键到技能的映射
     * @return 步骤标题
     */
    private static String titleOf(SkillItem skill, Map<String, Skill> skillByKey) {
        Skill found = skillByKey.get(skill.skillKey());
        return Objects.isNull(found) ? skill.skillKey() : found.title();
    }

    /**
     * skill 契约是否 {@link ExecutionMode#REACT}。
     *
     * @param skill 技能；可空
     * @return 契约模式为 REACT 时为 {@code true}
     */
    private static boolean isReactMode(@Nullable Skill skill) {
        if (Objects.isNull(skill)) {
            return false;
        }

        SkillContract contract = skill.contract();
        return Objects.nonNull(contract) && contract.modeOrDefault() == ExecutionMode.REACT;
    }

    /**
     * 规划用目标：metadata.goalSummary，否则用户原文。
     *
     * @param task 当前任务元数据；可空
     * @param ctx  本轮上下文
     * @return 非空目标文案；没有摘要时退回用户原文，再没有则为空串
     */
    private static String goalOf(@Nullable TaskMetadata task, TurnContext ctx) {
        if (Objects.nonNull(task) && Strings.isNotBlank(task.getGoalSummary())) {
            return task.getGoalSummary().trim();
        }

        return Objects.toString(ctx.getRequest().getUserInput(), "");
    }

    /**
     * 不建图的原因。
     */
    private enum SkipReason {

        /**
         * 本轮没有决策
         */
        NO_DECISION,

        /**
         * 续跑沿用已有计划
         */
        LOAD_EXISTING,

        /**
         * 本轮模式不建计划
         */
        NO_PLAN
    }

    /**
     * 规划失败的原因。
     */
    private enum FailReason {

        /**
         * 缺任务或技能
         */
        MISSING_TASK_OR_SKILLS,

        /**
         * 技能目录为空
         */
        EMPTY_CATALOG,

        /**
         * 筛不出候选技能
         */
        NO_CANDIDATE,

        /**
         * 保底后步骤仍为空
         */
        EMPTY_STEPS,

        /**
         * 补 provider 前置节点失败
         */
        PROVIDER
    }

    /**
     * 计划步骤的来源。
     */
    private enum PlanSource {

        /**
         * 模型出图
         */
        LLM,

        /**
         * 单个节点内循环技能，跳过模型出图
         */
        SINGLE_REACT,

        /**
         * 模型无步骤，按候选串行保底
         */
        DEGRADED
    }
}
