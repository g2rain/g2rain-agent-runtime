package com.g2rain.agent.runtime.engine.flow.graph;

import com.g2rain.agent.runtime.domain.execute.ExecutorResult;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.NodeEvent;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.react.ChatTurn;
import com.g2rain.agent.runtime.domain.react.ReActLoopContext;
import com.g2rain.agent.runtime.domain.react.ReActLoopResult;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.progress.StepStage;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.NodeAction;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
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
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.agent.runtime.event.StepEvents;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceScope;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.support.ChatMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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
     * 节点状态机：选中与循环结果后的状态迁移。
     */
    private final NodeStateMachine nodeStateMachine;

    /**
     * 节点工具集：内置工具筛选 + 技能 MCP 工具。
     */
    private final NodeToolset nodeToolset;

    /**
     * 未知写收口：verify 工具回查，调不了则 VERIFY 挂起。
     */
    private final WriteVerifier writeVerifier;

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
     * 证据流：用户确认后的证据收口。
     */
    private final EvidenceFlow evidenceFlow;

    /**
     * 执行步骤的记录与推送。
     */
    private final StepEvents stepEvents;

    /**
     * 在节点轨迹作用域内跑单个节点：节点内所有日志带节点 ID，并输出开始与结束轨迹。
     *
     * @param node  要跑的节点
     * @param frame 当前图执行帧
     * @return 本拍副作用列表
     */
    @Override
    public List<GraphEffect> run(TaskNode node, GraphFrame frame) {
        try (TraceScope ignored = TraceScope.node(node.getNodeId())) {
            long start = System.nanoTime();
            Trace.at(TraceStage.NODE, "node_start").kv("skillKey", node.getSkillKey()).kv("action", frame.action()).info();
            List<GraphEffect> effects = execute(node, frame);
            Trace.at(TraceStage.NODE, "node_end")
                .kv("status", node.getStatus())
                .kv("errorCode", node.getErrorCode())
                .kv("durationMs", (System.nanoTime() - start) / 1_000_000L)
                .info();
            return effects;
        }
    }

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
    private List<GraphEffect> execute(TaskNode node, GraphFrame frame) {
        List<GraphEffect> effects = new ArrayList<>();

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
            return effects;
        }

        // 真正要跑才开节点步骤，并把节点推进 RUNNING；结束时按节点终态收尾步骤
        String startedCopy = Strings.isNotBlank(node.getTitle()) ? node.getTitle().trim() : RuntimeMessage.STEP_TITLE_DEFAULT.text();
        String stepId = stepEvents.start(StepStage.NODE, startedCopy, node.getNodeId(), null);
        boolean completed = false;
        try {
            List<GraphEffect> result = proceed(node, frame, skill, nodeScope, effects);
            completed = true;
            return result;
        } finally {
            finishStep(stepId, node, completed);
        }
    }

    /**
     * 节点已开跑：组历史与工具 → 写回查 / 确认续跑 → ReAct 循环 → 按结果产副作用。
     *
     * @param node      当前节点
     * @param frame     当前图执行帧
     * @param skill     节点技能；可空
     * @param nodeScope 已打开的节点空间
     * @param effects   本拍副作用列表
     * @return 本拍副作用列表
     */
    private List<GraphEffect> proceed(TaskNode node, GraphFrame frame, @Nullable Skill skill, NodeScope nodeScope,
                                      List<GraphEffect> effects) {
        node.setStatus(nodeStateMachine.transit(NodeEvent.SELECTED, node.getStatus()).to());
        frame.turn().mark(node.getNodeId(), node.getStatus(), null, null);

        // 历史与可见工具表必须在循环外组好，循环内只追加本轮消息
        List<ToolCallback> tools = nodeToolset.of(node, frame);
        List<ChatMessage> history = historyFor(node, frame, nodeScope, tools);
        Trace.at(TraceStage.NODE, "node_ready")
            .kv("historySize", history.size())
            .kv("toolCount", tools.size())
            .kv("tools", tools.stream().map(t -> t.getToolDefinition().name()).toList())
            .info();

        if (nodeScope instanceof DefaultNodeScope defaultNode) {
            applyVerifyResume(defaultNode, frame, history);
            if (writeVerifier.haltUnknown(node, frame, skill, tools, defaultNode, effects)) {
                return effects;
            }
        }

        // 用户确认续跑：先把挂起上的待批写动作真正派发，再进循环
        if (GraphResumeActions.isConfirm(frame.action())) {
            dispatchPending(node, history, frame, nodeScope);
        }

        // 节点内循环；循环结果决定节点终态，本方法不直接调控流工具
        ReActLoopResult nodeResult = reActLoop.run(new ReActLoopContext(frame, node, nodeScope, history, tools));
        if (nodeResult.nodeStatus() == NodeStatus.DONE && Strings.isNotBlank(nodeResult.message())) {
            node.setResult(nodeResult.message().trim());
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
     * 按节点终态收尾节点步骤；异常中断按失败处理。
     *
     * @param stepId    节点步骤；可空
     * @param node      刚跑完的节点
     * @param completed 是否正常返回
     */
    private void finishStep(@Nullable String stepId, TaskNode node, boolean completed) {
        if (!completed) {
            stepEvents.fail(stepId, RuntimeMessage.STEP_FAILED.text(), null, null);
            return;
        }
        switch (node.getStatus()) {
            case FAILED -> stepEvents.fail(stepId,
                Strings.defaultIfBlank(node.getErrorMessage(), RuntimeMessage.STEP_FAILED.text()), null, null);
            case SUSPENDED -> stepEvents.done(stepId, RuntimeMessage.STEP_WAITING.text());
            case WAITING_DELEGATE -> stepEvents.done(stepId, RuntimeMessage.STATUS_PREPARING.text());
            case CANCELLED -> stepEvents.done(stepId, RuntimeMessage.STEP_CANCELLED.text());
            default -> stepEvents.done(stepId, null);
        }
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
            // 断点在节点被唤醒时已消费，完成时无需再清
            case LOOP_DONE -> effects;
            case LOOP_SUSPENDED -> {
                String message = Strings.defaultIfBlank(result.message(), RuntimeMessage.STEP_WAITING.text());
                effects.add(new GraphEffect.SaveSuspend(nodeId, result.checkpointType(), message, result.pendingAction(), result.missingParameters(), result.suggestions()));
                effects.add(new GraphEffect.Halt(new ExecutorResult(TaskStatus.SUSPENDED, nodeId, message, result.checkpointType())));
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
     * @param tools     本节点可见工具
     * @return 可变对话历史；至少可能被种入一条用户目标
     */
    private List<ChatMessage> historyFor(TaskNode node, GraphFrame frame, NodeScope nodeScope, List<ToolCallback> tools) {
        List<ChatMessage> history = new ArrayList<>();
        for (ChatTurn turn : runtimeStateFacade.history(nodeScope, tools)) {
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
                yield ChatMessages.assistant(content);
            }
            case TOOL -> ChatMessages.tool(Objects.toString(turn.toolCallId(), "tool"))
                .name(Strings.defaultIfBlank(turn.toolName(), "tool"))
                .content(content)
                .build();
        };
    }

    /**
     * 历史仍空时，优先开任务时的用户原文，再节点目标，再本轮原文。
     *
     * @param history 当前对话历史
     * @param frame   当前图执行帧
     * @param node    当前节点
     */
    private static void seedUserGoalIfAbsent(List<ChatMessage> history, GraphFrame frame, TaskNode node) {
        if (!history.isEmpty()) {
            return;
        }

        String goal = frame.turn().task().map(TaskMetadata::getRequest).filter(Strings::isNotBlank).orElse(null);
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
     * 确认续跑时执行已消费断点上的待批工具：先记下用户确认，再派发；确认与结果写入本节点 history，并清 pending。
     *
     * @param node      当前节点
     * @param history   本节点对话历史
     * @param frame     当前图执行帧
     * @param nodeScope 当前节点作用域
     */
    private void dispatchPending(TaskNode node, List<ChatMessage> history, GraphFrame frame, NodeScope nodeScope) {
        var checkpoint = frame.turn().resumed().orElse(null);
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

        List<ToolCallback> tools = nodeToolset.of(node, frame);
        ToolCallback toolCallback = ToolCallbacks.find(tools, pending.toolName());
        if (Objects.isNull(toolCallback)) {
            log.warn("dispatchPending tool missing nodeId={} tool={}",
                node.getNodeId(), pending.toolName()
            );
            return;
        }

        // 用户确认单独成轮：记在派发结果之前，跨轮重建时顺序为"确认 → 执行 → 结果"
        nodeScope.round(0);
        recordUser(nodeScope, history, ModelHint.USER_CONFIRMED.text("tool", pending.toolName()));

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

        // 派发后清掉已消费断点上的 pending，同轮再次进入节点不会重复派发
        checkpoint.setPendingAction(null);
    }

    /**
     * VERIFY 检查点上用户确认/拒绝：把未知写证据收成 SUCCESS / FAILED，并记下用户的回应。
     *
     * @param node    当前节点作用域
     * @param frame   当前图执行帧
     * @param history 本节点对话历史
     */
    private void applyVerifyResume(DefaultNodeScope node, GraphFrame frame, List<ChatMessage> history) {
        boolean verify = frame.turn().resumed()
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
        ModelHint said = action == ResumeAction.CONFIRM ? ModelHint.USER_VERIFIED : ModelHint.USER_UNVERIFIED;
        boolean opened = false;
        for (EvidenceHead head : List.copyOf(node.evidence())) {
            if (!WriteVerifier.isUnknownWrite(head)) {
                continue;
            }
            evidenceFlow.applyOutcome(node, head, status, businessOk, FailureKind.NONE);
            // 用户的回应单独成轮，排在之后的模型调用之前
            if (!opened) {
                node.round(0);
                opened = true;
            }
            recordUser(node, history, said.text("tool", head.toolName()));
        }
    }

    /**
     * 记一条用户对挂起的回应：写入节点动作供跨轮重建，同时追加到本轮历史。
     *
     * @param node    当前节点
     * @param history 本节点对话历史
     * @param text    回应描述
     */
    private void recordUser(NodeScope node, List<ChatMessage> history, String text) {
        runtimeStateFacade.act(node, NodeAction.user(node.rounds(), text));
        history.add(ChatMessages.user(text));
    }
}
