package com.g2rain.agent.runtime.engine.flow.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.plan.LlmPlanNode;
import com.g2rain.agent.runtime.domain.plan.PlanStep;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.model.ModelGateway;
import com.g2rain.agent.runtime.engine.model.ModelUse;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.prompt.PlanPrompt;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.support.ChatCompletionRequests;
import com.g2rain.model.nexus.support.OpenAiChatClientConverter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模型出图：按目标、前情与候选技能手册生成计划步骤，并校验节点、依赖与完成目标。
 *
 * @author alpha
 * @since 2026/10/12
 */
@Component
@RequiredArgsConstructor
public class PlanDrafter {

    /**
     * JSON 编解码器：拼 prompt、解析模型应答。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();


    /**
     * 模型网关。
     */
    private final ModelGateway modelGateway;


    /**
     * 调模型按用户目标与候选技能排出步骤；解析/校验失败返回 {@code null}。
     *
     * <p>输入数据字段用途见 {@link PlanView}；提示词见 {@link PlanPrompt}。
     *
     * @param ctx        本轮上下文
     * @param candidates 候选技能
     * @param skillByKey 按 skillKey 查找完整技能
     * @param goal       规划目标
     * @return 计划步骤；解析或校验失败则为 {@code null}
     */
    @Nullable List<PlanStep> draft(TurnContext ctx, List<SkillItem> candidates, Map<String, Skill> skillByKey, String goal) {
        try {
            // 候选技能换成带操作手册的完整条目；一个都找不到则不出图
            List<PlanSkillItem> planSkills = candidates.stream()
                .map(SkillItem::skillKey)
                .map(skillByKey::get)
                .filter(Objects::nonNull)
                .map(PlanSkillItem::from)
                .toList();

            if (Collections.isEmpty(planSkills)) {
                return null;
            }

            // 组装规划输入：用户原话、目标、前情摘要、Agent 与候选技能
            PlanMemory memory = planMemoryOf(ctx);
            AgentItem agentItem = AgentItem.from(ctx.getAgentSelection());
            String userPrompt = JSON.obj2str(new PlanView(
                ctx.getRequest().getUserInput(),
                goal,
                memory.continuitySummary(),
                agentItem,
                planSkills
            ));

            ChatMessage assistant = OpenAiChatClientConverter.firstMessage(modelGateway.chat(ModelUse.PLAN, ctx.getRequest().getModel(),
                ChatCompletionRequests.of().system(PlanPrompt.SYSTEM).user(PlanPrompt.USER.formatted(userPrompt)).build()
            ));
            if (Objects.isNull(assistant)) {
                return null;
            }

            String content = assistant.getContent();
            if (Strings.isBlank(content)) {
                return null;
            }

            // 只接受候选内的技能，解析与校验交给 resolve
            String planStr = RuntimeUtils.extractObject(content);
            Set<String> allowedSkills = candidates.stream().map(SkillItem::skillKey).collect(Collectors.toSet());
            return resolve(JSON.str2obj(planStr, LlmPlanAnswer.class), allowedSkills, skillByKey);
        } catch (RuntimeException ex) {
            Trace.at(TraceStage.PLAN, "llm_failed")
                .kv("error", ex.getClass().getSimpleName() + ": " + ex.getMessage())
                .warn();
            return null;
        }
    }

    /**
     * 校验并转换模型规划结果：节点完整、skill 在候选内、依赖合法无环；不合法的 target 丢弃。
     *
     * @param answer        模型规划 JSON；可空
     * @param allowedSkills 允许使用的 skillKey
     * @param skillByKey    按 skillKey 查找完整技能
     * @return 合法步骤列表；无法采用则为 {@code null}
     */
    private @Nullable List<PlanStep> resolve(@Nullable LlmPlanAnswer answer, Set<String> allowedSkills,
                                             Map<String, Skill> skillByKey) {
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

            nodes.add(checkTarget(PlanStep.from(node), skillByKey.get(node.skillKey())));
        }

        if (!PlanGraphs.valid(nodes)) {
            return null;
        }

        return nodes;
    }

    /**
     * 规划给出的 target 须是该技能的写工具；不是则丢弃，节点回落到契约默认目标。
     *
     * @param step  规划步骤
     * @param skill 步骤所用技能；可空
     * @return 原步骤或去掉 target 的步骤
     */
    private static PlanStep checkTarget(PlanStep step, @Nullable Skill skill) {
        String target = step.target();
        if (Objects.isNull(target)) {
            return step;
        }

        if (Objects.nonNull(skill) && skill.writeTools().contains(target)) {
            return step;
        }

        Trace.at(TraceStage.PLAN, "target_dropped")
            .kv("nodeId", step.nodeId())
            .kv("skillKey", step.skillKey())
            .kv("target", target)
            .warn();
        return step.withTarget(null);
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
     * 规划所需的技能摘抄：包含 key、操作手册和写工具，从 skills 回查，不与筛选短目录混用。
     *
     * @param skillKey    技能键
     * @param skillManual 操作手册
     * @param writeTools  契约声明的写工具，供规划选节点 target
     */
    private record PlanSkillItem(String skillKey, String skillManual, List<WriteTool> writeTools) {
        static PlanSkillItem from(Skill skill) {
            SkillContract contract = Objects.requireNonNullElseGet(skill.contract(), SkillContract::new);
            List<WriteTool> writeTools = skill.writeTools().stream()
                .map(name -> new WriteTool(name, Objects.requireNonNull(contract.tool(name)).getLabel()))
                .toList();
            return new PlanSkillItem(skill.skillKey(), Objects.toString(skill.skillManual(), ""), writeTools);
        }
    }

    /**
     * 规划可选的写工具。
     *
     * @param name  工具注册名
     * @param label 展示名；可空
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record WriteTool(String name, @Nullable String label) {

    }
}
