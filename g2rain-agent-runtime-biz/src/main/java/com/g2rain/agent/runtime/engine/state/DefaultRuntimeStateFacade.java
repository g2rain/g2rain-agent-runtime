package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.EdgeType;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.react.ChatTurn;
import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.dialogue.MessageType;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.DelegateResult;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.NodeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.domain.tool.ToolCall;
import com.g2rain.agent.runtime.domain.tool.ToolResult;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.engine.state.repo.CallRepository;
import com.g2rain.agent.runtime.engine.state.repo.ConversationRepository;
import com.g2rain.agent.runtime.engine.state.repo.PayloadRepository;
import com.g2rain.agent.runtime.engine.store.StateStore;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.PlatformCatalog;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceEvent;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContextHolder;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link RuntimeStateFacade} 的默认实现。
 *
 * <p>负责把会话回灌、节点打开、工具证据登记、热状态刷新和持久化串接起来；具体 Redis
 * 键、数据库写入和证据归一化分别委托给专用组件，避免状态门面承载底层存储细节。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultRuntimeStateFacade implements RuntimeStateFacade {

    /**
     * 处理平台注入和证据报文的 JSON 编解码器。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 将 Redis/MySQL 状态重建为本轮内存视图。
     */
    private final TurnSessionHydrate hydrate;

    /**
     * 生成并执行热状态刷新操作。
     */
    private final TurnFlushPlan flushPlan;

    /**
     * 处理工具调用前登记、结果结算和异常状态。
     */
    private final EvidenceFlow evidenceFlow;

    /**
     * 运行时热状态存储适配器。
     */
    private final StateStore store;

    /**
     * 轮末冷库落库编排。
     */
    private final RuntimeStatePersistence persistence;

    /**
     * 会话仓储：入口登记用户消息。
     */
    private final ConversationRepository conversations;

    /**
     * 工具调用仓储：证据报文冷读。
     */
    private final CallRepository calls;

    /**
     * 报文仓储：大报文冷读。
     */
    private final PayloadRepository payloads;

    /**
     * 从完成任务和工具结果中提取可复用实体。
     */
    private final EntityExtractor entityExtractor;

    /**
     * 捕获当前请求可注入的租户和用户身份信息。
     */
    private final PlatformCatalog platformCatalog;

    /**
     * 运行时配置：无证据动作的条数与说明上限。
     */
    private final RuntimeProperties properties;

    /**
     * 从热态或冷存储装载会话，并登记当前请求的用户消息。
     *
     * @param request 当前请求
     * @return 本轮可变状态视图
     */
    @Override
    public TurnScope begin(TurnRequest request) {
        DefaultTurnScope turn = hydrate.load(request);
        // 本轮租户/用户快照挂上，后续契约绑定从平台字段取，不写会话记忆
        turn.attachPlatform(platformCatalog.capture(PrincipalContextHolder.get()));
        turn.attachChannel(request.getChannel());
        // 中断规整的 STARTED→UNKNOWN 先落 Redis，避免本轮崩溃后仍显示进行中
        flushPlan.apply(turn, false);
        if (Strings.isBlank(request.getUserInput())) {
            // 纯确认/拒绝等无原文，不登记用户消息
            return turn;
        }

        DialogueMessage user = new DialogueMessage(DialogueRole.USER, flushPlan.clipMessage(request.getUserInput()));
        user.setMessageId(RuntimeUtils.uuid());
        user.setTraceId(turn.traceId());
        user.setType(MessageType.USER_TEXT);
        user.setModel(Strings.blankToNull(Strings.trim(request.getModel())));
        user.setTaskId(turn.conversation().activeTaskId());
        turn.checkpoint().filter(checkpoint -> checkpoint.getType() == CheckpointType.FOLLOW_UP).ifPresent(_ -> {
            String nodeId = turn.task().map(TaskMetadata::getCurrentNodeId).orElse(null);
            if (Strings.isBlank(nodeId)) {
                return;
            }
            // 追问挂在当前节点的下一 ReAct 轮，供 history 按 round 还原
            user.setNodeId(nodeId);
            int rounds = turn.node(nodeId).map(NodeScope::rounds)
                .orElseGet(() -> hydrate.openSpace(turn, SessionKeys.of(turn), nodeId).rounds());
            user.setRound(rounds + 1);
        });
        // 用户原文进交互历史后再刷热态，保证 Redis 与随后 MySQL 同源
        turn.append(user);
        flushPlan.apply(turn, false);

        try {
            // 热态已 flush；交互历史再落 MySQL，失败则整轮 begin 作废
            conversations.insertMessage(turn.conversation().conversationId(), user.getTaskId(), user, request.getChannel());
        } catch (RuntimeException e) {
            log.error("failed to begin", e);
            throw new BusinessException(RuntimeErrorCode.STATE_WRITE_FAILED);
        }

        return turn;
    }

    /**
     * 打开节点空间，修复未收口证据并绑定依赖节点输出。
     *
     * @param turn     当前轮视图
     * @param nodeId   节点 id
     * @param contract 节点技能契约；可空
     * @return 当前节点状态视图
     */
    @Override
    public NodeScope open(TurnScope turn, String nodeId, @Nullable SkillContract contract) {
        DefaultTurnScope scope = asTurn(turn);
        DefaultNodeScope node = hydrate.openSpace(scope, SessionKeys.of(scope), nodeId);
        List<EvidenceHead> repaired = node.evidence().stream().map(head -> {
            if (Objects.equals(head.status(), EvidenceStatus.UNKNOWN_PRE_CALL)) {
                // 上次崩溃停在调用前：证据不得再当进行中，改取消以免误复用
                return evidenceFlow.cancelPreCall(head);
            }
            return head;
        }).toList();
        repaired.forEach(node::addEvidence);
        settleDelegateAwaiting(scope, node);
        Map<String, Map<String, Sourced<?>>> upstream = new LinkedHashMap<>();
        predecessors(scope, nodeId).forEach(pred -> {
            var graphNode = scope.graph().find(pred);
            // 未完成上游不进绑定，避免把半成品当输入
            if (Objects.isNull(graphNode) || !Objects.equals(graphNode.getStatus(), NodeStatus.DONE)) {
                return;
            }
            upstream.put(pred, Map.copyOf(hydrate.openSpace(scope, SessionKeys.of(scope), pred).outputs()));
        });
        // 只绑 DONE 上游 outputs；未完成节点不进本节点任务记忆入参
        node.bind(InputBinder.resolve(scope, nodeId, contract, upstream));
        return node;
    }

    /**
     * 子节点已终态时结算委派等待：校验 DELEGATE 边，清 awaiting；失败写入 {@link NodeNoteKey#DELEGATE_RESULT}。
     *
     * @param turn 本轮视图
     * @param node 正在打开的父节点
     * @throws BusinessException 等待的子节点或委派边缺失
     */
    private void settleDelegateAwaiting(DefaultTurnScope turn, DefaultNodeScope node) {
        Object awaiting = node.note(NodeNoteKey.AWAITING);
        if (Objects.isNull(awaiting) || Strings.isBlank(String.valueOf(awaiting))) {
            return;
        }
        String childId = String.valueOf(awaiting).trim();
        var graph = turn.graph();
        boolean edgeOk = graph.getEdges().stream()
            .anyMatch(edge -> edge.getType() == EdgeType.DELEGATE
                && childId.equals(edge.getFrom())
                && node.nodeId().equals(edge.getTo()));
        TaskNode child = graph.find(childId);
        if (!edgeOk || Objects.isNull(child)) {
            throw new BusinessException(RuntimeErrorCode.DELEGATE_BROKEN, childId);
        }
        if (!child.getStatus().isTerminal()) {
            // 子节点仍在跑，父节点继续等待
            return;
        }
        node.note(NodeNoteKey.AWAITING, null);
        TraceEvent settled = Trace.at(TraceStage.NODE, "delegate_done")
            .kv("childId", childId)
            .kv("skillKey", child.getSkillKey())
            .kv("status", child.getStatus())
            .kv("errorCode", child.getErrorCode());
        if (child.getStatus() == NodeStatus.DONE) {
            settled.info();
            // 成功委派不残留失败便签
            node.note(NodeNoteKey.DELEGATE_RESULT, null);
            return;
        }

        settled.warn();
        node.note(NodeNoteKey.DELEGATE_RESULT,
            new DelegateResult(child.getSkillKey(), child.getStatus(), child.getErrorCode(), child.getErrorMessage()));
    }

    /**
     * 执行写工具调用前的证据登记和幂等检查。
     *
     * @param node 当前节点
     * @param call 待执行工具调用
     * @return 允许继续、复用结果或阻断调用的预写结论
     */
    @Override
    public PrewriteResult prewrite(NodeScope node, ToolCall call) {
        EvidenceFlow.requireWrite(call);
        DefaultNodeScope current = asNode(node);
        return adopt(current, call, evidenceFlow.prewrite(current, call));
    }

    /**
     * 查看任务内是否已有同一写操作的生效证据；复用其他节点的成功结果时补记到本节点。
     *
     * @param node 当前节点
     * @param call 写工具调用
     * @return 复用或阻断结论；没有生效证据为空
     */
    @Override
    public Optional<PrewriteResult> existing(NodeScope node, ToolCall call) {
        EvidenceFlow.requireWrite(call);
        DefaultNodeScope current = asNode(node);
        return evidenceFlow.existing(current, call).map(result -> adopt(current, call, result));
    }

    /**
     * 复用的成功证据不在本节点时，以原报文在本节点记一条成功证据；其余结论原样返回。
     *
     * @param node   当前节点
     * @param call   写工具调用
     * @param result 预写结论
     * @return 原结论
     */
    private PrewriteResult adopt(DefaultNodeScope node, ToolCall call, PrewriteResult result) {
        if (!(result instanceof PrewriteResult.Reused(String evidenceId))
            || node.evidence().stream().anyMatch(head -> Objects.equals(head.evidenceId(), evidenceId))) {
            return result;
        }

        String taskId = node.turn().task().map(TaskMetadata::getTaskId).orElse(null);
        JsonNode body = Strings.isBlank(taskId) ? NullNode.getInstance() : payload(node.turn(), taskId, evidenceId);
        evidenceFlow.record(node, call, new ToolResult(EvidenceStatus.SUCCESS, body, null, null, Map.of(),
            FailureKind.NONE, null, ModelHint.WRITE_REUSED.text(), null, 0L));
        return result;
    }

    /**
     * 把写工具结果结算到已登记证据，并更新节点摘要。
     *
     * @param node       当前节点
     * @param evidenceId 预写返回的证据 id
     * @param result     工具结果
     */
    @Override
    public void settle(NodeScope node, String evidenceId, ToolResult result) {
        evidenceFlow.settle(asNode(node), evidenceId, result);
    }

    /**
     * 记录一次真实发起的非写工具调用。
     *
     * @param node   当前节点
     * @param call   工具调用
     * @param result 工具结果
     * @return 新证据 id
     */
    @Override
    public String record(NodeScope node, ToolCall call, ToolResult result) {
        return evidenceFlow.record(asNode(node), call, result);
    }

    /**
     * 按内存、Redis、库表依次取证据报文。
     *
     * @param turn       本轮视图
     * @param taskId     任务 id
     * @param evidenceId 证据 id
     * @return 报文；三处都没有时为 JSON null 节点
     */
    @Override
    public JsonNode payload(TurnScope turn, String taskId, String evidenceId) {
        DefaultTurnScope scope = asTurn(turn);
        JsonNode cached = scope.payloads().get(evidenceId);
        // 本轮内存 → Redis 热态 → 库表冷态；三处都没有才给 JSON null
        if (Objects.nonNull(cached)) {
            return cached;
        }
        String raw = store.get(SessionKeys.of(scope).payload(taskId, evidenceId));
        if (Strings.isNotBlank(raw)) {
            return JSON.str2node(raw);
        }
        String cold = calls.response(evidenceId);
        if (Strings.isBlank(cold)) {
            cold = payloads.loadArtifact(evidenceId);
        }
        return Strings.isBlank(cold) ? NullNode.getInstance() : JSON.str2node(cold);
    }

    /**
     * 打开指定节点空间并返回其证据摘要列表。
     *
     * @param turn   本轮视图
     * @param nodeId 节点 id
     * @return 该节点当前证据摘要
     */
    @Override
    public List<EvidenceHead> evidenceOf(TurnScope turn, String nodeId) {
        return hydrate.openSpace(asTurn(turn), SessionKeys.of(turn), nodeId).evidence();
    }

    /**
     * 汇总已完成依赖节点的 outputs，键为 {@code nodeId.key}。
     *
     * @param turn    本轮视图
     * @param nodeIds 依赖节点 id
     * @return 仅含 DONE 节点的产出；未完成节点跳过
     */
    @Override
    public Map<String, Sourced<?>> outputsOf(TurnScope turn, List<String> nodeIds) {
        DefaultTurnScope scope = asTurn(turn);
        Map<String, Sourced<?>> outputs = new LinkedHashMap<>();
        nodeIds.forEach(nodeId -> {
            var graphNode = scope.graph().find(nodeId);
            // 规划读产出时同样跳过未完成节点
            if (Objects.isNull(graphNode) || !Objects.equals(graphNode.getStatus(), NodeStatus.DONE)) {
                return;
            }
            hydrate.openSpace(scope, SessionKeys.of(scope), nodeId).outputs()
                .forEach((key, value) -> outputs.put(nodeId + "." + key, value));
        });
        return outputs;
    }

    /**
     * 按轮次合并本节点的对话消息、业务证据与无证据动作，重建为 ReAct {@link ChatTurn} 列表（见 {@link NodeHistory}）。
     *
     * @param node  当前节点
     * @param tools 本节点可见工具，用于识别追问调用
     * @return 含 USER / ASSISTANT / TOOL 的跨轮历史
     */
    @Override
    public List<ChatTurn> history(NodeScope node, List<ToolCallback> tools) {
        DefaultNodeScope current = asNode(node);
        String taskId = current.turn().task().map(TaskMetadata::getTaskId).orElse(null);
        return NodeHistory.build(current, tools, evidenceId -> Strings.isBlank(taskId)
            ? NullNode.getInstance()
            : payload(current.turn(), taskId, evidenceId));
    }

    /**
     * 记录一条无证据动作：裁剪说明后追加到节点，超过上限丢弃最早的。
     *
     * @param node   当前节点
     * @param action 动作
     */
    @Override
    public void act(NodeScope node, NodeAction action) {
        RuntimeProperties.State state = properties.getState();
        String clipped = Objects.requireNonNull(RuntimeUtils.clip(
            action.message(), state.getNodeActionMaxChars()
        ));
        asNode(node).act(action.withMessage(clipped), state.getNodeActionsMax());

    }

    /**
     * 把本轮内存改动刷入 Redis；不写库表。
     *
     * @param turn 本轮视图
     */
    @Override
    public void flush(TurnScope turn) {
        flushPlan.apply(asTurn(turn), false);
    }

    /**
     * 先写库表，再把已关闭任务从热态摘掉，最后带 expire 刷 Redis。
     *
     * <p>库表写入失败会立即重试一次；两次都失败则中止，避免热态已摘任务而库表未落地。</p>
     *
     * @param turn 本轮视图
     */
    @Override
    public void persist(TurnScope turn) {
        DefaultTurnScope scope = asTurn(turn);
        try {
            persistOnce(scope);
        } catch (RuntimeException first) {
            try {
                persistOnce(scope);
            } catch (RuntimeException second) {
                log.error("[DefaultRuntimeStateFacade] persist failed conversationId={}",
                    scope.conversation().conversationId(), second);
                // 两次库表失败都不摘热态任务，避免丢权威记录
                return;
            }
        }
        // 库表已成功：才从热态摘已关闭任务，避免 Redis 先丢权威记录
        scope.closedTasks().forEach(closed -> {
            TaskMetadata task = closed.metadata();
            TaskRecord record = TaskRecords.of(task, scope);
            flushPlan.dropClosedTask(scope, task, closed.graph(), closed.payloadIds(), record);
        });
        scope.task().filter(task -> TaskStatus.isTerminal(task.getStatus())).ifPresent(task -> {
            TaskRecord record = TaskRecords.of(task, scope);
            flushPlan.dropClosedTask(scope, record);
            scope.clearActiveTask();
        });
        flushPlan.apply(scope, true);
    }

    /**
     * 抽取实体并写入本轮关系数据，成功后标记交互历史已落库。
     *
     * @param scope 本轮视图
     */
    private void persistOnce(DefaultTurnScope scope) {
        scope.task().ifPresent(_ -> entityExtractor.collect(scope));
        persistence.persistTurn(scope);
        // 标记后 pending 交互历史与执行步骤不再二次落库
        scope.markDialoguePersisted();
        scope.progress().markPersisted();
    }

    /**
     * 图上指向该节点的上游 id。
     *
     * @param turn   本轮
     * @param nodeId 当前节点
     * @return 上游 nodeId
     */
    private List<String> predecessors(DefaultTurnScope turn, String nodeId) {
        var node = turn.graph().find(nodeId);
        if (Objects.isNull(node)) {
            return List.of();
        }
        return List.copyOf(node.getDependsOn());
    }

    /**
     * 本实现只接受 {@link DefaultTurnScope}，其它实现无法写热态。
     *
     * @param turn 调用方传入的本轮视图
     * @return 默认可写实现
     * @throws BusinessException 类型不匹配
     */
    private static DefaultTurnScope asTurn(TurnScope turn) {
        if (turn instanceof DefaultTurnScope scope) {
            return scope;
        }
        throw new BusinessException(RuntimeErrorCode.STATE_WRITE_FAILED);
    }

    /**
     * 本实现只接受 {@link DefaultNodeScope}。
     *
     * @param node 调用方传入的节点视图
     * @return 默认可写实现
     * @throws BusinessException 类型不匹配
     */
    private static DefaultNodeScope asNode(NodeScope node) {
        if (node instanceof DefaultNodeScope scope) {
            return scope;
        }
        throw new BusinessException(RuntimeErrorCode.STATE_WRITE_FAILED);
    }
}
