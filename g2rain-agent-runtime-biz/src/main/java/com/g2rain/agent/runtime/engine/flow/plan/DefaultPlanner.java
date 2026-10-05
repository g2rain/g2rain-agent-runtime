package com.g2rain.agent.runtime.engine.flow.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.hydrate.ExecutionMode;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.plan.LlmPlanNode;
import com.g2rain.agent.runtime.domain.plan.PlanStep;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;
import com.g2rain.agent.runtime.engine.TurnResult;
import com.g2rain.agent.runtime.engine.model.ModelGateway;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.prompt.PlanPrompt;
import com.g2rain.agent.runtime.support.prompt.SkillCandidatePrompt;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.support.ChatCompletionRequests;
import com.g2rain.model.nexus.support.OpenAiChatClientConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultPlanner implements Planner {

    /**
     * JSON 编解码器：拼 prompt、解析模型规划结果。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 模型网关：技能候选与出步骤两次 chat。
     */
    private final ModelGateway modelGateway;

    /**
     * 运行时配置：规划候选技能数量上限等。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 规划本轮任务图并经 Facade 写入当前 Task。
     *
     * <ol>
     *   <li>无 Decision → return
     *   <li>{@code loadExistingPlan}（续跑沿用已有图）→ return
     *   <li>非 {@code createPlan}（不必建图）→ return
     *   <li>校验 Task + {@code ctx.skills}；缺则 failed
     *   <li>skills → 短目录；{@code selectCandidates} 筛本轮候选；空则 failed
     *   <li>取 goal（metadata.goalSummary，否则用户原文）
     *   <li>单候选且契约 {@code executionMode=REACT} → 单节点简图（跳过 LLM 出图）
     *   <li>否则 LLM 出图（prompt 含 goal / 前情 / 手册）；失败 → 候选串行保底
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
            log.info("[Planner] skip (no decision)");
            return;
        }

        // 续跑沿用热态已有图，不再出新计划
        if (decision.loadExistingPlan()) {
            log.info("[Planner] skip loadExistingPlan taskId={}",
                ctx.getTurn().task().map(TaskMetadata::getTaskId).orElse(null)
            );

            return;
        }

        // 仅回复等模式明确不建图
        if (!decision.createPlan()) {
            log.info("[Planner] skip createPlan=false mode={}", decision.mode());
            return;
        }

        // 新建计划必须已有任务和技能；缺任一则失败，避免画出空图
        var turn = ctx.getTurn();
        TaskMetadata task = turn.task().orElse(null);
        List<Skill> skills = ctx.getSkills();
        if (Objects.isNull(task) || Strings.isBlank(task.getTaskId()) || Collections.isEmpty(skills)) {
            log.info("[Planner] fail missing task/skills taskPresent={} skillCount={}",
                Objects.nonNull(task), Objects.nonNull(skills) ? skills.size() : 0
            );

            failPlan(ctx, RuntimeMessage.PLAN_FAILED.text());
            return;
        }

        // ⑤ skills → 短目录；筛本轮候选
        Map<String, Skill> skillByKey = skills.stream().collect(Collectors.toMap(
            Skill::skillKey, Function.identity(), (a, _) -> a, LinkedHashMap::new
        ));

        List<SkillItem> allSkills = skills.stream().map(SkillItem::from).toList();
        if (Collections.isEmpty(allSkills)) {
            log.info("[Planner] fail empty skill catalog taskId={}", task.getTaskId());
            failPlan(ctx, RuntimeMessage.PLAN_FAILED.text());
            return;
        }

        List<SkillItem> candidates = selectCandidates(ctx, allSkills);
        // 筛不出候选宁可失败，也不把全量技能硬画进图
        if (Collections.isEmpty(candidates)) {
            log.info("[Planner] fail empty candidates taskId={}", task.getTaskId());
            failPlan(ctx, RuntimeMessage.PLAN_FAILED.text());
            return;
        }

        // ⑥ 取 goal
        String goal = goalOf(task, ctx);
        log.info("[Planner] begin taskId={} goal={} candidateKeys={}",
            task.getTaskId(), goal, JSON.obj2str(candidates)
        );

        List<PlanStep> steps;
        // 单候选且契约就是节点内循环：跳过 LLM 出图，避免为一步任务再调一次模型
        if (candidates.size() == 1 && isReactMode(skillByKey.get(candidates.getFirst().skillKey()))) {
            steps = List.of(singleReactStep(candidates.getFirst(), goal));
            log.info("[Planner] 单候选 executionMode=REACT → 跳过 LLM 出图 skillKey={}",
                candidates.getFirst().skillKey()
            );
        } else {
            // LLM 空步骤用候选串行保底，仍保证有图可执行
            steps = planByLlm(ctx, candidates, skillByKey, goal);
            if (Collections.isEmpty(steps)) {
                steps = degradeFromCandidates(candidates, goal);
                log.info("[Planner] llm empty → degrade steps={}", steps);
            } else {
                log.info("[Planner] llm steps={}", steps);
            }
        }

        // 保底后仍空：标失败，不把空图写进任务记忆
        if (Collections.isEmpty(steps)) {
            log.info("[Planner] fail empty steps taskId={}", task.getTaskId());
            failPlan(ctx, RuntimeMessage.PLAN_FAILED.text());
            return;
        }

        // ⑩ 落图、补 provider 前置、focus；空 goal 才补
        TaskGraph graph = PlanGraphs.materialize(steps);
        try {
            ProviderNodes.expand(graph, skillByKey, turn);
        } catch (BusinessException ex) {
            log.info("[Planner] fail provider taskId={} code={}", task.getTaskId(), ex.getErrorCode());
            failPlan(ctx, RuntimeMessage.PLAN_FAILED.text());
            return;
        }
        String taskId = task.getTaskId();
        String focusNodeId = PlanGraphs.firstReadyNodeId(graph);
        turn.plan(graph);
        if (Strings.isNotBlank(focusNodeId)) {
            turn.focus(focusNodeId);
        }
        // 开任务时未写下 goal 才用本轮规划目标回填，避免覆盖意图摘要
        if (Strings.isBlank(task.getGoalSummary()) && Strings.isNotBlank(goal)) {
            task.setGoalSummary(goal.trim());
        }

        log.info("[Planner] done taskId={} nodeCount={} firstReady={}",
            taskId, graph.getNodes().size(), focusNodeId
        );
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
     * 从白名单筛本轮候选技能。
     *
     * <p>白名单只有 1 个则全用；多个才调模型缩名单，失败退回全量。
     * user JSON 字段用途见 {@link SkillCandidateView}；system 见 {@link SkillCandidatePrompt}。
     *
     * @param ctx       本轮上下文
     * @param whitelist 技能白名单
     * @return 筛后的候选；模型给出空名单时为空列表，调用异常时退回全量白名单
     */
    private List<SkillItem> selectCandidates(TurnContext ctx, List<SkillItem> whitelist) {
        if (whitelist.size() == 1) {
            return whitelist;
        }

        try {
            RuntimeProperties.Plan cfg = runtimeProperties.getPlan();
            String goal = goalOf(ctx.getTurn().task().orElse(null), ctx);
            String userPrompt = JSON.obj2str(new SkillCandidateView(
                ctx.getRequest().getUserInput(), goal, whitelist
            ));

            ChatMessage assistant = OpenAiChatClientConverter.firstMessage(modelGateway.chat(
                ChatCompletionRequests.of().system(SkillCandidatePrompt.TEXT).user(userPrompt).build()
            ));
            if (Objects.isNull(assistant) || Strings.isBlank(assistant.getContent())) {
                return whitelist;
            }

            // 从模型正文抠出候选 skillKey 列表
            List<String> keys;
            try {
                String raw = RuntimeUtils.extractObject(assistant.getContent());
                CandidateSkillsAnswer answer = JSON.str2obj(raw, CandidateSkillsAnswer.class);
                if (Objects.isNull(answer) || Collections.isEmpty(answer.candidateSkillKeys())) {
                    // 模型给出空名单：视为筛不出候选，交给上层失败，而不是退回全量
                    return List.of();
                }

                keys = answer.candidateSkillKeys().stream()
                    .filter(Strings::isNotBlank)
                    .map(String::trim)
                    .toList();
            } catch (RuntimeException ex) {
                keys = List.of();
            }

            Set<String> white = whitelist.stream().map(SkillItem::skillKey).collect(Collectors.toSet());
            Map<String, SkillItem> byKey = whitelist.stream().collect(Collectors.toMap(
                SkillItem::skillKey, s -> s, (a, _) -> a
            ));

            int max = Math.max(1, cfg.getMaxCandidates());
            List<SkillItem> ordered = keys.stream()
                .filter(white::contains)
                .distinct()
                .map(byKey::get)
                .filter(Objects::nonNull)
                .limit(max)
                .toList();

            return ordered.isEmpty() ? whitelist : ordered;
        } catch (RuntimeException ex) {
            // 调用或解析异常：不阻断规划，退回全量候选
            return whitelist;
        }
    }

    /**
     * 调模型按用户目标与候选技能排出步骤；解析/校验失败返回 {@code null}。
     *
     * <p>user JSON 字段用途见 {@link PlanView}；system 见 {@link PlanPrompt}。
     *
     * @param ctx        本轮上下文
     * @param candidates 候选技能
     * @param skillByKey 按 skillKey 查找完整技能
     * @param goal       规划目标
     * @return 计划步骤；解析或校验失败则为 {@code null}
     */
    private @Nullable List<PlanStep> planByLlm(TurnContext ctx, List<SkillItem> candidates, Map<String, Skill> skillByKey, String goal) {
        try {
            List<PlanSkillItem> planSkills = candidates.stream()
                .map(SkillItem::skillKey)
                .map(skillByKey::get)
                .filter(Objects::nonNull)
                .map(PlanSkillItem::from)
                .toList();

            if (Collections.isEmpty(planSkills)) {
                return null;
            }

            PlanMemory memory = planMemoryOf(ctx);
            AgentItem agentItem = AgentItem.from(ctx.getAgentSelection());
            String userPrompt = JSON.obj2str(new PlanView(
                ctx.getRequest().getUserInput(),
                goal,
                memory.continuitySummary(),
                agentItem,
                planSkills
            ));

            ChatMessage assistant = OpenAiChatClientConverter.firstMessage(modelGateway.chat(
                ChatCompletionRequests.of().system(PlanPrompt.TEXT).user(userPrompt).build()
            ));
            if (Objects.isNull(assistant)) {
                return null;
            }

            String content = assistant.getContent();
            if (Strings.isBlank(content)) {
                return null;
            }

            String planStr = RuntimeUtils.extractObject(content);
            Set<String> allowedSkills = candidates.stream().map(SkillItem::skillKey).collect(Collectors.toSet());
            return resolve(JSON.str2obj(planStr, LlmPlanAnswer.class), allowedSkills);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 校验并转换模型规划结果：节点完整、skill 在候选内、依赖合法无环。
     *
     * @param answer        模型规划 JSON；可空
     * @param allowedSkills 允许使用的 skillKey
     * @return 合法步骤列表；无法采用则为 {@code null}
     */
    private @Nullable List<PlanStep> resolve(@Nullable LlmPlanAnswer answer, Set<String> allowedSkills) {
        if (Objects.isNull(answer)) {
            return null;
        }

        List<LlmPlanNode> planNodes = answer.nodes();
        if (Collections.isEmpty(planNodes)) {
            return null;
        }

        List<PlanStep> nodes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (LlmPlanNode node : planNodes) {
            if (node.incomplete()) {
                return null;
            }

            // noinspection DataFlowIssue
            if (!ids.add(node.nodeId())) {
                return null;
            }

            if (!allowedSkills.contains(node.skillKey())) {
                return null;
            }

            nodes.add(PlanStep.from(node));
        }

        if (!PlanGraphs.valid(nodes)) {
            return null;
        }

        return nodes;
    }

    /**
     * 模型失败时的保底计划：候选技能按顺序串成一条依赖链（边落图时 NORMAL）。
     *
     * @param candidates 候选技能
     * @param goal       规划目标；可空时用默认步骤标题
     * @return 保底步骤；无候选时为空列表
     */
    private static List<PlanStep> degradeFromCandidates(
        List<SkillItem> candidates, @Nullable String goal) {
        if (Collections.isEmpty(candidates)) {
            return List.of();
        }

        String objective = Strings.isNotBlank(goal) ? goal.trim() : RuntimeMessage.STEP_TITLE_DEFAULT.text();
        return IntStream.range(0, candidates.size()).mapToObj(i -> {
            SkillItem skill = candidates.get(i);
            String nodeId = "n" + (i + 1);
            List<String> dependsOn = i == 0 ? List.of() : List.of("n" + i);
            return new PlanStep(nodeId, skill.skillKey(), objective, skill.skillKey(), dependsOn);
        }).toList();
    }

    /**
     * 单候选 REACT：一个无依赖节点。
     *
     * @param skill 唯一候选技能
     * @param goal  规划目标；可空时用默认步骤标题
     * @return 单节点计划步骤
     */
    private static PlanStep singleReactStep(SkillItem skill, @Nullable String goal) {
        String objective = Strings.isNotBlank(goal) ? goal.trim() : RuntimeMessage.STEP_TITLE_DEFAULT.text();
        return new PlanStep("n1", skill.skillKey(), objective, skill.skillKey(), List.of());
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
     * 规划用前情：{@link Prior#reason()}。
     *
     * @param ctx 本轮上下文
     * @return 任务前情切片；没有 Prior 时为空记忆
     */
    private PlanMemory planMemoryOf(TurnContext ctx) {
        return ctx.getTurn().prior()
            .map(prior -> new PlanMemory(Strings.isBlank(prior.reason()) ? null : prior.reason()))
            .orElseGet(PlanMemory::empty);
    }

    /**
     * 技能候选模型的 JSON 形状。
     *
     * @param candidateSkillKeys 模型挑出的 skillKey；可空
     */
    private record CandidateSkillsAnswer(@Nullable List<String> candidateSkillKeys) {

    }

    /**
     * 规划模型的 JSON 形状：节点列表。
     *
     * @param nodes 模型给出的规划节点；可空
     */
    private record LlmPlanAnswer(@Nullable List<LlmPlanNode> nodes) {

    }

    /**
     * Plan prompt 用的任务前情。
     *
     * @param continuitySummary 上一 Task 综述；可空
     */
    private record PlanMemory(@Nullable String continuitySummary) {
        static PlanMemory empty() {
            return new PlanMemory(null);
        }
    }

    /**
     * 注入规划 prompt 的人设摘抄。
     *
     * @param profileText 人设正文；可空
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record AgentItem(@Nullable String profileText) {
        static AgentItem from(@Nullable AgentSelection selection) {
            if (Objects.isNull(selection)) {
                return new AgentItem(null);
            }

            return new AgentItem(selection.getProfileText());
        }
    }

    /**
     * 技能候选筛选的 user JSON。
     *
     * <pre>
     * input  — 用户原话，用来匹配相关技能
     * goal   — 目标摘要，与 input 互补（要对准办成什么）
     * skills — skillKey + semanticDesc：只能从这里挑候选；短描述做相关判断
     * </pre>
     *
     * @param input  用户原话；可空时按空串入模型
     * @param goal   目标摘要；可空时按空串入模型
     * @param skills 候选短目录
     */
    private record SkillCandidateView(@Nullable String input, @Nullable String goal, List<SkillItem> skills) {
        SkillCandidateView {
            input = Objects.toString(input, "");
            goal = Objects.toString(goal, "");
            skills = List.copyOf(skills);
        }
    }

    /**
     * 技能筛选所需的短目录：仅包含 key 和语义短描述，不含操作手册。
     *
     * @param skillKey     技能键
     * @param semanticDesc 语义短描述
     */
    private record SkillItem(String skillKey, String semanticDesc) {
        static SkillItem from(Skill skill) {
            return new SkillItem(skill.skillKey(), Objects.toString(skill.semanticDesc(), ""));
        }
    }

    /**
     * 任务规划的 user JSON。
     *
     * <pre>
     * input              — 用户原话
     * goal               — 要对准拆节点的目标
     * continuitySummary  — 上一 Task 综述：让新计划接上已有结论，避免重做或打架
     * agent.profileText  — 人设：只帮理解任务，不扩能力
     * skills             — skillKey+skillManual：节点只能从这里选技能；手册看边界
     * </pre>
     *
     * @param input             用户原话；可空时按空串入模型
     * @param goal              规划目标；可空时按空串入模型
     * @param continuitySummary 任务前情综述；可空
     * @param agent             人设摘抄
     * @param skills            规划技能手册目录
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record PlanView(@Nullable String input, @Nullable String goal, @Nullable String continuitySummary,
                            AgentItem agent, List<PlanSkillItem> skills) {
        PlanView {
            input = Objects.toString(input, "");
            goal = Objects.toString(goal, "");
            skills = List.copyOf(skills);
        }
    }

    /**
     * 规划所需的技能摘抄：包含 key 和操作手册，从 skills 回查，不与筛选短目录混用。
     *
     * @param skillKey    技能键
     * @param skillManual 操作手册
     */
    private record PlanSkillItem(String skillKey, String skillManual) {
        static PlanSkillItem from(Skill skill) {
            return new PlanSkillItem(skill.skillKey(), Objects.toString(skill.skillManual(), ""));
        }
    }
}
