package com.g2rain.agent.runtime.engine.tools.builtin;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.EdgeType;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskEdge;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.state.EntityExtractor;
import com.g2rain.agent.runtime.engine.tools.ToolExecutionContexts;
import com.g2rain.agent.runtime.engine.tools.policy.FinishContext;
import com.g2rain.agent.runtime.engine.tools.policy.FinishDecision;
import com.g2rain.agent.runtime.engine.tools.policy.FinishPolicy;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCategory;
import com.g2rain.agent.runtime.support.tool.BuiltinToolKind;
import com.g2rain.agent.runtime.support.tool.BuiltinTools;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 控流内置工具：追问、审批、委派、完成、失败。
 *
 * <p>模型在节点内循环里调用；实现从 Spring ToolContext 取出本仓执行上下文后改节点状态。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Component
@RequiredArgsConstructor
public class ControlBuiltinTools implements BuiltinTools {

    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * finish 门槛（targetTool / doneWhen 等）
     */
    private final FinishPolicy finishPolicy;

    /**
     * 委派深度上限。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * finish 后沉淀 output 实体候选与 share。
     */
    private final EntityExtractor entityExtractor;

    /**
     * 向用户追问（含补参）。
     *
     * <p>当前节点改为挂起（SUSPENDED）并记下追问检查点（FOLLOW_UP）：问题、待补参数与快捷建议。
     * 人回复后运行时再续跑本节点循环。{@code expectedFields} 未传或 null 视为空列表。
     * 例：{@code followUp("请补充收货地址", List.of("address"), List.of("家里"), toolContext)}</p>
     *
     * @param question       追问文案，会回给用户看
     * @param expectedFields 待补参数名列表；可空，空则当空列表
     * @param suggestions    快捷气泡建议文案；可空
     * @param toolContext    Spring AI 工具上下文，内含本节点执行现场
     * @return 控流后的节点状态名与追问文案
     */
    @Tool(
        name = "builtin_node_follow_up",
        description = """
            向用户追问 (含补参), 节点挂起, 等人回复后续跑
            """
    )
    @BuiltinToolKind(category = BuiltinToolCategory.CONTROL, action = BuiltinToolAction.ASK)
    public NodeActionResult followUp(@ToolParam(description = "追问文案") String question,
                                     @ToolParam(required = false, description = "待补参数名") @Nullable List<String> expectedFields,
                                     @ToolParam(required = false, description = "快捷气泡建议") @Nullable List<String> suggestions,
                                     ToolContext toolContext) {
        ToolExecutionContext ctx = ToolExecutionContexts.requireSpring(toolContext);
        expectedFields = Objects.requireNonNullElse(expectedFields, List.of());
        ctx.applyFollowUp(question, expectedFields, suggestions);
        return new NodeActionResult(ctx.getNodeStatus().name(), question);
    }

    /**
     * 请求用户批准被写闸拦下的那次写调用。
     *
     * <p>只接受写闸以 APPROVAL_REQUIRED 拦下、且工具名一致的调用；未经写工具直接申请则拒绝。
     * 待批动作取闸门记下的绑定后参数与摘要，节点改为挂起（SUSPENDED）并记下审批检查点（APPROVAL）；
     * 人确认后由运行时派发。
     * 例：{@code approval("将改订单地址", "order_update", toolContext)}</p>
     *
     * @param summary     给人看的审批摘要
     * @param toolName    被拦下的写工具名
     * @param toolContext Spring AI 工具上下文，内含本节点执行现场
     * @return 控流后的节点状态名与摘要
     */
    @Tool(
        name = "builtin_node_approval",
        description = """
            写工具被拦下要求确认后调用: 请求用户批准该次调用, 节点挂起, 人确认后 Runtime 派发
            """
    )
    @BuiltinToolKind(category = BuiltinToolCategory.CONTROL, action = BuiltinToolAction.APPROVAL)
    public NodeActionResult approval(@ToolParam(description = "给人看的摘要") String summary,
                                     @ToolParam(description = "被拦下的写工具名") String toolName,
                                     ToolContext toolContext) {
        ToolExecutionContext ctx = ToolExecutionContexts.requireSpring(toolContext);
        requireWriteTool(ctx, toolName);
        PendingAction gated = gatedWrite(ctx, toolName);
        ctx.scope().note(NodeNoteKey.GATED_WRITE, null);
        ctx.applyApproval(summary, gated);
        return new NodeActionResult(ctx.getNodeStatus().name(), summary);
    }

    /**
     * 委派前置步骤：插入子节点先跑，当前节点进入等待委派（WAITING_DELEGATE）。
     *
     * <p>{@code skillKey} 必须在本节点可委派技能目录内，且不超过委派深度上限。
     * 子节点 READY，父依赖子；子完成后唤醒父继续。同一失败/取消/跳过的委派不可原样再插。
     * 例：{@code delegate("lookup_address", "先查出收货地址", toolContext)}</p>
     *
     * @param skillKey    前置步骤技能键
     * @param objective   前置步骤说明；可空
     * @param toolContext Spring AI 工具上下文，内含本节点执行现场
     * @return 控流后的节点状态名与技能键
     */
    @Tool(
        name = "builtin_node_delegate",
        description = """
            委派前置步骤：插入前置节点先执行，当前步骤进入等待，前置完成后唤醒继续
            """
    )
    @BuiltinToolKind(category = BuiltinToolCategory.CONTROL, action = BuiltinToolAction.DELEGATE)
    public NodeActionResult delegate(@ToolParam(description = "前置步骤技能键") String skillKey,
                                     @ToolParam(required = false, description = "前置步骤说明") @Nullable String objective,
                                     ToolContext toolContext) {
        ToolExecutionContext ctx = ToolExecutionContexts.requireSpring(toolContext);
        delegate(ctx, skillKey, objective);
        return new NodeActionResult(ctx.getNodeStatus().name(), skillKey);
    }

    /**
     * 本节点完成：结束本节点循环。
     *
     * <p>先过 finish 门槛（targetTool / doneWhen 等）。通过则节点标 DONE、落下 output，并沉淀实体候选。
     * 未达标只记下拒绝原因，节点状态不变，循环可继续。{@code output} 须是给用户看的结论。
     * 例：{@code finish("已查到 2 条待发货订单", toolContext)}</p>
     *
     * @param output      节点正式产出
     * @param toolContext Spring AI 工具上下文，内含本节点执行现场
     * @return 控流后的节点状态名；finish 被拒时 message 为拒绝原因，通过时 message 为空
     */
    @Tool(
        name = "builtin_node_finish",
        description = """
            本节点完成, 结束本节点循环。output 必须是给用户看的结论（查询写清有哪些或没有），禁止空。
            """
    )
    @BuiltinToolKind(category = BuiltinToolCategory.CONTROL, action = BuiltinToolAction.FINISH)
    public NodeActionResult finish(@ToolParam(description = "节点正式产出") String output, ToolContext toolContext) {
        ToolExecutionContext ctx = ToolExecutionContexts.requireSpring(toolContext);
        finish(ctx, output);
        return new NodeActionResult(ctx.getNodeStatus().name(), ctx.getFinishRejectedReason());
    }

    /**
     * 本节点失败：结束本节点循环并标记 FAILED。
     *
     * <p>写入失败原因并清挂起类型。后续调度按失败节点处理，不再续跑本循环。
     * 例：{@code fail("下游接口超时", toolContext)}</p>
     *
     * @param reason      失败原因
     * @param toolContext Spring AI 工具上下文，内含本节点执行现场
     * @return 控流后的节点状态名与失败原因
     */
    @Tool(
        name = "builtin_node_fail",
        description = """
            本节点失败: 结束本节点循环并标记失败
            """
    )
    @BuiltinToolKind(category = BuiltinToolCategory.CONTROL, action = BuiltinToolAction.FAIL)
    public NodeActionResult fail(@ToolParam(description = "失败原因") String reason, ToolContext toolContext) {
        ToolExecutionContext ctx = ToolExecutionContexts.requireSpring(toolContext);
        ctx.applyFail(reason);
        return new NodeActionResult(ctx.getNodeStatus().name(), reason);
    }

    /**
     * 控流工具统一回包：节点状态名 + 可选说明。
     *
     * @param nodeStatus 控流后的节点状态名
     * @param message    追问文案 / 摘要 / 技能键 / 拒绝原因等；可空
     */
    public record NodeActionResult(String nodeStatus, @Nullable String message) {

    }

    /**
     * 插入子节点并让父节点等待委派。
     *
     * @param ctx       节点执行现场
     * @param skillKey  子步骤技能键
     * @param objective 子目标说明；可空
     */
    private void delegate(ToolExecutionContext ctx, String skillKey, @Nullable String objective) {
        if (Strings.isBlank(skillKey)) {
            throw new BusinessException(RuntimeErrorCode.SKILL_NOT_FOUND, "skillKey");
        }

        List<String> catalog = ctx.getDelegatableSkillKeys();
        if (!catalog.contains(skillKey.trim())) {
            throw new BusinessException(RuntimeErrorCode.DELEGATE_FORBIDDEN, skillKey);
        }

        TaskGraph graph = ctx.getGraph();
        TaskNode parent = graph.find(ctx.getNodeId());
        if (Objects.isNull(parent)) {
            throw new BusinessException(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, "parent");
        }

        if (parent.getDelegateDepth() + 1 > runtimeProperties.getState().getDelegateMaxDepth()) {
            throw new BusinessException(RuntimeErrorCode.DELEGATE_FORBIDDEN, "depth");
        }

        String parentId = parent.getNodeId();
        boolean repeated = graph.getEdges().stream()
            .filter(edge -> edge.getType() == EdgeType.DELEGATE && parentId.equals(edge.getTo()))
            .map(edge -> graph.find(edge.getFrom()))
            .filter(Objects::nonNull)
            .anyMatch(node ->
                skillKey.trim().equals(node.getSkillKey())
                    && Objects.equals(objective, node.getObjective())
                    && (node.getStatus() == NodeStatus.FAILED
                    || node.getStatus() == NodeStatus.CANCELLED
                    || node.getStatus() == NodeStatus.SKIPPED)
            );

        if (repeated) {
            throw new BusinessException(RuntimeErrorCode.DELEGATE_REPEATED, skillKey);
        }

        // 新建 READY 前置节点，挂到图上
        String childId = RuntimeUtils.uuid();
        TaskNode child = new TaskNode();
        child.setNodeId(childId);
        child.setSkillKey(skillKey.trim());
        child.setObjective(objective);
        child.setStatus(NodeStatus.READY);
        child.setDelegateDepth(parent.getDelegateDepth() + 1);
        child.setDependsOn(new ArrayList<>());
        graph.addNode(child);

        // 父依赖子：子先跑，父进入 WAITING_DELEGATE
        List<String> dependsOn = parent.getDependsOn();
        if (!dependsOn.contains(childId)) {
            dependsOn.add(childId);
            graph.getEdges().add(new TaskEdge(
                childId, parent.getNodeId(), EdgeType.DELEGATE
            ));
        }

        ctx.scope().note(NodeNoteKey.AWAITING, childId);
        ctx.applyWaitingDelegate();
    }

    /**
     * 审批只能挂写工具。
     *
     * @param ctx      节点执行现场
     * @param toolName 待审批的工具名
     */
    private static void requireWriteTool(ToolExecutionContext ctx, String toolName) {
        SkillContract contract = ctx.contract();
        ToolSpec spec = Objects.isNull(contract) ? null : contract.tool(toolName);
        if (Objects.isNull(spec) || spec.sideEffectOrDefault() != SideEffect.WRITE) {
            throw new BusinessException(RuntimeErrorCode.WRITE_CONTRACT_INCOMPLETE, toolName);
        }
    }

    /**
     * 取写闸记下的待批调用。
     *
     * @param ctx      节点执行现场
     * @param toolName 申请审批的工具名
     * @return 被拦下的调用（绑定后参数 + 摘要）
     */
    private static PendingAction gatedWrite(ToolExecutionContext ctx, String toolName) {
        Object raw = ctx.scope().note(NodeNoteKey.GATED_WRITE);
        PendingAction gated = switch (raw) {
            case null -> null;
            case PendingAction typed -> typed;
            // 跨轮水合后便签为通用结构，按 JSON 还原
            default -> JSON.str2obj(JSON.obj2str(raw), PendingAction.class);
        };
        if (Objects.isNull(gated) || !Objects.equals(gated.toolName(), toolName)) {
            throw new BusinessException(RuntimeErrorCode.APPROVAL_NOT_GATED, toolName);
        }
        return gated;
    }

    /**
     * 完成节点：先过 finish 门槛，通过后落产出 / provides，再结束循环。
     *
     * @param ctx    节点执行现场
     * @param output 节点正式产出
     */
    private void finish(ToolExecutionContext ctx, Object output) {
        // 门槛未过：只记拒绝原因，节点仍可继续循环
        FinishDecision decision = finishPolicy.evaluate(new FinishContext(output, ctx));

        if (!decision.allowed()) {
            ctx.applyFinishRejected(Objects.requireNonNullElse(decision.reason(), ModelHint.FINISH_REJECTED.text()));
            return;
        }

        ctx.applyFinish(output);
        entityExtractor.captureOutputs(ctx.scope(), ctx.contract());
    }
}
