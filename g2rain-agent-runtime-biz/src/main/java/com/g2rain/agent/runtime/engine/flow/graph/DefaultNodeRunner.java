package com.g2rain.agent.runtime.engine.flow.graph;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.execute.ExecutorResult;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.NodeEvent;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.hydrate.VerifySpec;
import com.g2rain.agent.runtime.domain.react.ChatTurn;
import com.g2rain.agent.runtime.domain.react.ReActLoopContext;
import com.g2rain.agent.runtime.domain.react.ReActLoopResult;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.engine.flow.plan.ProviderNodes;
import com.g2rain.agent.runtime.engine.flow.react.ReActLoop;
import com.g2rain.agent.runtime.engine.state.DefaultNodeScope;
import com.g2rain.agent.runtime.engine.state.EvidenceFlow;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.ToolInvoker;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.agent.runtime.support.mcp.McpToolCallbackProvider;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCallbackProvider;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.support.ChatMessages;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 默认节点执行器：打开节点作用域、跑 ReAct，并按循环结果产出挂起、完成或失败副作用。
 *
 * <p>provider 输出已满足时可 SKIPPED。确认续跑会先派发待批写工具。
 * VERIFY 续跑只改证据态，不派发工具。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultNodeRunner implements NodeRunner {

    /**
     * 解析待批参数哈希与 verify 入参时使用的 JSON 编解码器。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 节点状态机：选中与循环结果后的状态迁移。
     */
    private final NodeStateMachine nodeStateMachine;

    /**
     * 内置工具回调提供方。
     */
    private final BuiltinToolCallbackProvider builtinTools;

    /**
     * MCP 同步客户端：按技能声明装配外部工具。
     */
    private final McpSyncClient mcpSyncClient;

    /**
     * 节点内循环：思考 → 调工具 → 观察，直到完成/挂起/失败。
     */
    private final ReActLoop reActLoop;

    /**
     * 工具调用入口：CONFIRM 待批派发。
     */
    private final ToolInvoker toolInvoker;

    /**
     * 运行时状态门面：进节点 {@code open}、读 history。
     */
    private final RuntimeStateFacade runtimeStateFacade;

    /**
     * 证据流：未知写工具回查与用户确认收口。
     */
    private final EvidenceFlow evidenceFlow;

    /**
     * {@code mcp.max-retries}：listTools 建连失败额外重试。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 跑单个节点：open → 发开始事件 → 组历史与工具 → 跑节点内循环 → 按结果产副作用。
     *
     * <ol>
     *   <li>Facade {@code open}（NodeSpace + 证据摘要）
     *   <li>发 NODE_STARTED，节点推进 RUNNING
     *   <li>组装本节点对话消息（{@code facade.history}）
     *   <li>CONFIRM 时派发挂起上的待批工具
     *   <li>ReAct 循环 → 按结果产副作用
     * </ol>
     *
     * @param node  要跑的节点
     * @param frame 当前图执行帧
     * @return 本拍副作用列表
     */
    @Override
    public List<GraphEffect> run(TaskNode node, GraphFrame frame) {
        List<GraphEffect> effects = new ArrayList<>();
        log.info("[NodeRunner] start nodeId={} skillKey={} action={} taskId={}",
            node.getNodeId(), node.getSkillKey(), frame.action(), frame.taskId()
        );

        // 先打开节点空间并修未收口证据，后续循环都挂在这份 NodeScope 上
        Skill skill = frame.findSkill(node.getSkillKey());
        NodeScope nodeScope = runtimeStateFacade.open(
            frame.turn(), node.getNodeId(), Objects.isNull(skill) ? null : skill.contract()
        );

        // 共享/会话里已有未过期 provider 产出：直接 SKIPPED，不跑工具、也不发 RUNNING
        if (Objects.nonNull(skill) && ProviderNodes.skipSatisfied(frame.turn(), skill)) {
            ProviderNodes.copySharedOutputs(frame.turn(), nodeScope, skill);
            node.setStatus(NodeStatus.SKIPPED);
            frame.turn().mark(node.getNodeId(), NodeStatus.SKIPPED, null, null);
            effects.add(new GraphEffect.ClearSuspend(node.getNodeId()));
            return effects;
        }

        // 真正要跑才发过程提示，并把节点推进 RUNNING
        String startedCopy = Strings.isNotBlank(node.getTitle()) ? node.getTitle().trim() : RuntimeMessage.STATUS_WORKING.text();
        effects.add(new GraphEffect.Publish(node.getNodeId(), startedCopy));
        node.setStatus(nodeStateMachine.transit(NodeEvent.SELECTED, node.getStatus()).to());
        frame.turn().mark(node.getNodeId(), node.getStatus(), null, null);

        // 历史与可见工具表必须在循环外组好，循环内只追加本轮消息
        List<ChatMessage> history = historyFor(node, frame, nodeScope);
        List<ToolCallback> tools = toolsFor(node, frame);
        log.info("[NodeRunner] prepared nodeId={} historySize={} toolNames={}",
            node.getNodeId(), history.size(), tools.stream().map(t -> t.getToolDefinition().name()).toList()
        );

        if (nodeScope instanceof DefaultNodeScope defaultNode) {
            applyVerifyResume(defaultNode, frame);
            if (haltUnknownWrites(node, frame, skill, tools, defaultNode, effects)) {
                return effects;
            }
        }

        // 用户确认续跑：先把挂起上的待批写动作真正派发，再进循环
        if (GraphResumeActions.isConfirm(frame.action())) {
            dispatchPending(node, history, frame, nodeScope);
        }

        // 节点内循环；循环结果决定节点终态，本方法不直接调控流工具
        ReActLoopResult nodeResult = reActLoop.run(new ReActLoopContext(frame, node, nodeScope, history, tools));
        log.info("[NodeRunner] react done nodeId={} status={} message={} checkpointType={} suggestions={}",
            node.getNodeId(), nodeResult.nodeStatus(), nodeResult.message(), nodeResult.checkpointType(), nodeResult.suggestions()
        );
        if (nodeResult.nodeStatus() == NodeStatus.DONE) {
            frame.completionMessage(nodeResult.message());
        }

        // 用循环给出的事件过状态机，失败/取消才把错误码写进图节点
        NodeEvent event = nodeResult.nodeStatus().loopEvent();
        NodeTransition transition = nodeStateMachine.transit(event, node.getStatus());
        node.setStatus(transition.to());
        String errorCode = null;
        String errorMessage = null;
        if (node.getStatus() == NodeStatus.FAILED || node.getStatus() == NodeStatus.CANCELLED) {
            errorCode = node.getStatus().name();
            errorMessage = Strings.defaultIfBlank(nodeResult.message(), node.getStatus().name());
        }
        frame.turn().mark(node.getNodeId(), node.getStatus(), errorCode, errorMessage);

        // 副作用（清挂起/存检查点/停图）交给图内核统一应用，本方法只产出清单
        effects.addAll(effectsOf(frame.graph(), node, nodeResult, event));
        return effects;
    }

    /**
     * 按节点内循环事件组装要应用的图副作用。
     *
     * <p>DELEGATE 子节点失败或取消只发结束事件，不停整图。</p>
     *
     * @param graph  当前任务图，用于判断是否为委派子节点
     * @param node   刚跑完的节点
     * @param result 循环结果
     * @param event  由节点态推导的循环事件
     * @return 待应用的副作用
     */
    private static List<GraphEffect> effectsOf(
        TaskGraph graph, TaskNode node, ReActLoopResult result, NodeEvent event) {
        String nodeId = node.getNodeId();
        List<GraphEffect> effects = new ArrayList<>();
        boolean delegateChild = graph.getEdges().stream()
            .anyMatch(edge -> edge.isDelegate() && nodeId.equals(edge.getFrom()));
        return switch (event) {
            case LOOP_DONE -> {
                effects.add(new GraphEffect.ClearSuspend(nodeId));
                yield effects;
            }
            case LOOP_SUSPENDED -> {
                String message = Strings.defaultIfBlank(result.message(), RuntimeMessage.STEP_WAITING.text());
                effects.add(new GraphEffect.SaveSuspend(nodeId, result.checkpointType(), message, result.pendingAction(), result.missingParameters(), result.suggestions()));
                effects.add(new GraphEffect.Halt(new ExecutorResult(TaskStatus.SUSPENDED, nodeId, message, result.checkpointType())));
                yield effects;
            }
            case LOOP_WAITING_DELEGATE -> {
                effects.add(new GraphEffect.Publish(nodeId, RuntimeMessage.STATUS_PREPARING.text()));
                yield effects;
            }
            case LOOP_CANCELLED -> {
                String message = Strings.defaultIfBlank(result.message(), RuntimeMessage.STEP_CANCELLED.text());
                if (!delegateChild) {
                    effects.add(new GraphEffect.Halt(new ExecutorResult(TaskStatus.CANCELED, nodeId, message)));
                }
                yield effects;
            }
            case LOOP_FAILED -> {
                String message = Strings.defaultIfBlank(result.message(), RuntimeMessage.STEP_FAILED.text());
                if (!delegateChild) {
                    effects.add(new GraphEffect.Halt(new ExecutorResult(TaskStatus.FAILED, nodeId, message)));
                }
                yield effects;
            }
            default -> effects;
        };
    }

    /**
     * 组装本节点 ReAct 对话消息：跨轮 {@code facade.history}，空则种一条用户目标。
     *
     * @param node      当前节点
     * @param frame     当前图执行帧
     * @param nodeScope 当前节点作用域
     * @return 可变对话历史；至少可能被种入一条用户目标
     */
    private List<ChatMessage> historyFor(TaskNode node, GraphFrame frame, NodeScope nodeScope) {
        List<ChatMessage> history = new ArrayList<>();
        for (ChatTurn turn : runtimeStateFacade.history(nodeScope)) {
            history.add(toChatMessage(turn));
        }
        seedUserGoalIfAbsent(history, frame, node);
        return history;
    }

    /**
     * {@link ChatTurn} → 模型侧 {@link ChatMessage}。
     *
     * @param turn 持久化的一轮对话
     * @return 对应角色的模型消息
     */
    private static ChatMessage toChatMessage(ChatTurn turn) {
        String content = Objects.toString(turn.content(), "");
        return switch (turn.role()) {
            case USER -> ChatMessages.user(content);
            case ASSISTANT -> {
                if (Collections.isNotEmpty(turn.toolCalls())) {
                    var builder = ChatMessages.assistant().content(content);
                    for (ChatTurn.ToolCallPart part : turn.toolCalls()) {
                        builder.toolCall(tc -> tc.id(part.id())
                            .function(part.name(), Strings.defaultIfBlank(part.arguments(), "{}")));
                    }
                    yield builder.build();
                }
                if (Strings.isNotBlank(turn.toolCallId()) && Strings.isNotBlank(turn.toolName())) {
                    yield ChatMessages.assistant()
                        .content(content)
                        .toolCall(tc -> tc.id(turn.toolCallId())
                            .function(turn.toolName(), Strings.defaultIfBlank(turn.arguments(), "{}")))
                        .build();
                }
                yield ChatMessages.assistant(content);
            }
            case TOOL -> ChatMessages.tool(Objects.toString(turn.toolCallId(), "tool"))
                .name(Strings.defaultIfBlank(turn.toolName(), "tool"))
                .content(content)
                .build();
        };
    }

    /**
     * 历史仍空时，优先任务 goalSummary，再节点目标，再本轮原文。
     *
     * @param history 当前对话历史
     * @param frame   当前图执行帧
     * @param node    当前节点
     */
    private static void seedUserGoalIfAbsent(List<ChatMessage> history, GraphFrame frame, TaskNode node) {
        if (!history.isEmpty()) {
            return;
        }

        String goal = frame.turn().task().map(TaskMetadata::getGoalSummary).filter(Strings::isNotBlank).orElse(null);
        if (Strings.isBlank(goal)) {
            goal = node.getObjective();
        }
        if (Strings.isBlank(goal)) {
            goal = frame.userInput();
        }

        if (Strings.isBlank(goal)) {
            return;
        }

        history.add(ChatMessages.user(goal.trim()));
    }

    /**
     * 组装本节点可用工具：内置工具 + 技能声明的 MCP 工具。
     *
     * <p>业务 MCP 按 {@code skill.toolNames()} 挂载；内置控流始终保留。
     * {@code targetToolName} 只供完成策略锚定，不收窄可见工具。
     *
     * @param node  当前节点
     * @param frame 当前图执行帧
     * @return 内置工具加技能 MCP 工具；无技能时仅内置
     */
    private List<ToolCallback> toolsFor(TaskNode node, GraphFrame frame) {
        List<ToolCallback> result = new ArrayList<>(Arrays.asList(builtinTools.getToolCallbacks()));

        String sk = node.getSkillKey();
        if (Strings.isBlank(sk)) {
            return result;
        }

        Skill skill = frame.findSkill(sk);
        if (Objects.isNull(skill) || Collections.isEmpty(skill.toolNames())) {
            return result;
        }

        // 可调用以 toolNames 为准；contract.tools 仅可选配置
        LinkedHashSet<String> tools = new LinkedHashSet<>(skill.toolNames());
        if (tools.isEmpty()) {
            return result;
        }
        try {
            var mcpProvider = new McpToolCallbackProvider(
                mcpSyncClient, tools, runtimeProperties.getMcp().getMaxRetries()
            );
            result.addAll(Arrays.asList(mcpProvider.getToolCallbacks()));
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("MCP toolsFor failed nodeId={}", node.getNodeId(), ex);
            throw new BusinessException(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, ex, "MCP Registry");
        }

        return result;
    }

    /**
     * 确认续跑时执行挂起检查点上的待批工具；结果写入本节点 history，并清 pending。
     *
     * @param node      当前节点
     * @param history   本节点对话历史
     * @param frame     当前图执行帧
     * @param nodeScope 当前节点作用域
     */
    private void dispatchPending(TaskNode node, List<ChatMessage> history, GraphFrame frame, NodeScope nodeScope) {
        var checkpoint = frame.turn().checkpoint().orElse(null);
        PendingAction pending = Objects.isNull(checkpoint) ? null : checkpoint.getPendingAction();
        if (Objects.isNull(pending) || Strings.isBlank(pending.toolName())) {
            return;
        }
        // VERIFY 的 CONFIRM 只改证据态，不派发待批工具
        if (checkpoint.getType() == CheckpointType.VERIFY) {
            return;
        }

        String args = Strings.isBlank(pending.args()) ? "{}" : pending.args();
        if (Strings.isNotBlank(pending.argsHash())) {
            String current = RuntimeUtils.argsHash(JsonCodecFactory.instance().str2node(args));
            if (!Objects.equals(current, pending.argsHash())) {
                throw new BusinessException(RuntimeErrorCode.ARGS_HASH_MISMATCH);
            }
        }

        List<ToolCallback> tools = toolsFor(node, frame);
        ToolCallback toolCallback = ToolCallbacks.find(tools, pending.toolName());
        if (Objects.isNull(toolCallback)) {
            log.warn("dispatchPending tool missing nodeId={} tool={}",
                node.getNodeId(), pending.toolName()
            );
            return;
        }

        ToolExecutionContext toolContext = new ToolExecutionContext(frame, node, nodeScope);
        toolContext.markApprovedWrite();
        Observation observation = toolInvoker.invoke(new ToolInvokeRequest(
            toolContext, toolCallback, args
        ));

        String pendingId = "pending-" + pending.toolName();
        history.add(ChatMessages.assistant()
            .content("")
            .toolCall(tc -> tc.id(pendingId).function(pending.toolName(), args))
            .build());
        history.add(ChatMessages.tool(pendingId)
            .name(pending.toolName())
            .content(Objects.toString(observation.message(), ""))
            .build());

        // 派发后清热态 pending；不走 saveCheckpoint（会把 metadata 打回 SUSPENDED）
        checkpoint.setPendingAction(null);
    }

    /**
     * VERIFY 检查点上用户确认/拒绝：把未知写证据收成 SUCCESS / FAILED。
     *
     * @param node  当前节点作用域
     * @param frame 当前图执行帧
     */
    private void applyVerifyResume(DefaultNodeScope node, GraphFrame frame) {
        boolean verify = frame.turn().checkpoint()
            .map(checkpoint -> checkpoint.getType() == CheckpointType.VERIFY)
            .orElse(false);
        if (!verify) {
            return;
        }
        ResumeAction action = frame.action();
        EvidenceStatus status;
        Boolean businessOk;
        if (action == ResumeAction.CONFIRM) {
            status = EvidenceStatus.SUCCESS;
            businessOk = true;
        } else if (action == ResumeAction.REJECT) {
            status = EvidenceStatus.FAILED;
            businessOk = false;
        } else {
            return;
        }
        for (EvidenceHead head : List.copyOf(node.evidence())) {
            if (!isUnknownWrite(head)) {
                continue;
            }
            evidenceFlow.applyOutcome(node, head, status, businessOk, FailureKind.NONE);
        }
    }

    /**
     * 仍未知的写证据：先调 verify.tool；调不了则 VERIFY 挂起。
     *
     * @param node      当前节点
     * @param frame     当前图执行帧
     * @param skill     当前技能；可空
     * @param tools     本节点可见工具
     * @param nodeScope 当前节点作用域
     * @param effects   收集挂起副作用
     * @return 已发出 VERIFY 挂起时为 {@code true}
     */
    private boolean haltUnknownWrites(TaskNode node, GraphFrame frame, @Nullable Skill skill,
                                      List<ToolCallback> tools, DefaultNodeScope nodeScope,
                                      List<GraphEffect> effects) {
        for (EvidenceHead head : List.copyOf(nodeScope.evidence())) {
            if (!isUnknownWrite(head)) {
                continue;
            }
            if (settleByVerifyTool(node, frame, skill, tools, nodeScope, head)) {
                continue;
            }
            String ask = RuntimeMessage.VERIFY_ASK.text();
            node.setStatus(NodeStatus.SUSPENDED);
            frame.turn().mark(node.getNodeId(), NodeStatus.SUSPENDED, null, null);
            effects.add(new GraphEffect.SaveSuspend(node.getNodeId(), CheckpointType.VERIFY, ask, null, List.of(),
                List.of()));
            effects.add(new GraphEffect.Halt(new ExecutorResult(TaskStatus.SUSPENDED, node.getNodeId(), ask,
                CheckpointType.VERIFY)));
            return true;
        }
        return false;
    }

    /**
     * 调用契约 verify 工具收口未知写证据。
     *
     * @param node      当前节点
     * @param frame     当前图执行帧
     * @param skill     当前技能；可空
     * @param tools     本节点可见工具
     * @param nodeScope 当前节点作用域
     * @param head      待核实的写证据
     * @return 已调用 verify 工具则为 {@code true}；无工具可调则为 {@code false}
     */
    private boolean settleByVerifyTool(TaskNode node, GraphFrame frame, @Nullable Skill skill,
                                       List<ToolCallback> tools, DefaultNodeScope nodeScope, EvidenceHead head) {
        if (Objects.isNull(skill) || Objects.isNull(skill.contract())) {
            return false;
        }
        ToolSpec spec = skill.contract().tool(head.toolName());
        if (Objects.isNull(spec) || Objects.isNull(spec.getVerify()) || Strings.isBlank(spec.getVerify().getTool())) {
            return false;
        }
        VerifySpec verify = spec.getVerify();
        ToolCallback callback = ToolCallbacks.find(tools, verify.getTool());
        if (Objects.isNull(callback)) {
            return false;
        }
        ToolExecutionContext toolContext = new ToolExecutionContext(frame, node, nodeScope);
        Observation observation = toolInvoker.invoke(new ToolInvokeRequest(
            toolContext, callback, verifyArgs(verify, head)
        ));
        if (observation.ok()) {
            evidenceFlow.applyOutcome(nodeScope, head, EvidenceStatus.SUCCESS, true, FailureKind.NONE);
        } else {
            evidenceFlow.applyOutcome(nodeScope, head, EvidenceStatus.FAILED, false, FailureKind.NONE);
        }
        return true;
    }

    /**
     * 按 verify.args 从原始请求 JSON 抽出入参；无映射时整份请求透传。
     *
     * @param verify 工具上的核实声明
     * @param head   待核实的写证据
     * @return 调用 verify 工具的 JSON 参数
     */
    private String verifyArgs(VerifySpec verify, EvidenceHead head) {
        String request = evidenceFlow.requestJson(head.evidenceId());
        if (Collections.isEmpty(verify.getArgs())) {
            return Strings.isBlank(request) ? "{}" : request;
        }
        JsonNode original = Strings.isBlank(request) ? JSON.str2node("{}") : JSON.str2node(request);
        Map<String, Object> built = new LinkedHashMap<>();
        verify.getArgs().forEach((key, spec) -> {
            if (Objects.nonNull(spec.getConstant())) {
                built.put(key, spec.getConstant());
            } else if (Strings.isNotBlank(spec.getFrom()) && spec.getFrom().startsWith("call.")) {
                JsonNode value = JSON.lookupNode(original, spec.getFrom().substring("call.".length()));
                if (Objects.nonNull(value)) {
                    built.put(key, value);
                }
            }
        });
        return JSON.obj2str(built);
    }

    /**
     * 是否为尚未收口的写证据（UNKNOWN 或 UNKNOWN_PRE_CALL）。
     *
     * @param head 证据头
     * @return 仍是未知写证据时为 {@code true}
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean isUnknownWrite(EvidenceHead head) {
        if (head.sideEffect() != SideEffect.WRITE) {
            return false;
        }
        return head.status() == EvidenceStatus.UNKNOWN || head.status() == EvidenceStatus.UNKNOWN_PRE_CALL;
    }
}
