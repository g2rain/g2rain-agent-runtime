package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.converter.ChatConversationConverter;
import com.g2rain.agent.runtime.converter.TaskCheckpointConverter;
import com.g2rain.agent.runtime.converter.TaskGraphEdgeConverter;
import com.g2rain.agent.runtime.converter.TaskGraphNodeConverter;
import com.g2rain.agent.runtime.converter.TaskInstanceConverter;
import com.g2rain.agent.runtime.converter.ToolCallRecordConverter;
import com.g2rain.agent.runtime.domain.graph.EdgeType;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskEdge;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.dialogue.MessageType;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.ClosedTask;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.NodeSpace;
import com.g2rain.agent.runtime.domain.state.task.PayloadType;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.dto.ChatConversationDto;
import com.g2rain.agent.runtime.dto.ChatConversationSelectDto;
import com.g2rain.agent.runtime.dto.ChatMessageDto;
import com.g2rain.agent.runtime.dto.ChatMessageSelectDto;
import com.g2rain.agent.runtime.dto.TaskCheckpointDto;
import com.g2rain.agent.runtime.dto.TaskCheckpointSelectDto;
import com.g2rain.agent.runtime.dto.TaskGraphEdgeSelectDto;
import com.g2rain.agent.runtime.dto.TaskGraphNodeDto;
import com.g2rain.agent.runtime.dto.TaskGraphNodeSelectDto;
import com.g2rain.agent.runtime.dto.TaskInstanceDto;
import com.g2rain.agent.runtime.dto.TaskInstanceSelectDto;
import com.g2rain.agent.runtime.dto.TaskNodePayloadDto;
import com.g2rain.agent.runtime.dto.TaskNodePayloadSelectDto;
import com.g2rain.agent.runtime.dto.TaskResumeRecordDto;
import com.g2rain.agent.runtime.dto.ToolCallRecordDto;
import com.g2rain.agent.runtime.dto.ToolCallRecordSelectDto;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.service.ChatConversationService;
import com.g2rain.agent.runtime.service.ChatMessageService;
import com.g2rain.agent.runtime.service.TaskCheckpointService;
import com.g2rain.agent.runtime.service.TaskGraphEdgeService;
import com.g2rain.agent.runtime.service.TaskGraphNodeService;
import com.g2rain.agent.runtime.service.TaskInstanceService;
import com.g2rain.agent.runtime.service.TaskNodePayloadService;
import com.g2rain.agent.runtime.service.TaskResumeRecordService;
import com.g2rain.agent.runtime.service.ToolCallRecordService;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.vo.ChatConversationVo;
import com.g2rain.agent.runtime.vo.ChatMessageVo;
import com.g2rain.agent.runtime.vo.TaskCheckpointVo;
import com.g2rain.agent.runtime.vo.TaskGraphEdgeVo;
import com.g2rain.agent.runtime.vo.TaskGraphNodeVo;
import com.g2rain.agent.runtime.vo.TaskInstanceVo;
import com.g2rain.agent.runtime.vo.TaskNodePayloadVo;
import com.g2rain.agent.runtime.vo.ToolCallRecordVo;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Moments;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 运行时状态的冷存储读写组件。
 * <p>
 * 由 {@link DefaultRuntimeStateFacade} 在热态刷新后调用；
 * 写失败只打日志，不回滚 Redis；读失败返回 {@code null} 或空集合。
 * Redis 没有时，从库读回会话/元数据/检查点/执行图壳，再写热。
 * </p>
 *
 * @author alpha
 * @since 2026/9/10
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeStatePersistence {

    /**
     * 处理列表、来源值和待确认动作等结构化字段的 JSON 编解码器。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 反序列化字符串列表的类型引用。
     */
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {

    };

    /**
     * 共享值 Map。
     */
    private static final TypeReference<Map<String, Sourced<?>>> SHARED_MAP = new TypeReference<>() {

    };

    /**
     * 节点 inputs / outputs。
     */
    private static final TypeReference<Map<String, Sourced<?>>> SOURCED_MAP = new TypeReference<>() {

    };

    /**
     * 节点私有便签。
     */
    private static final TypeReference<Map<String, Object>> STATE_MAP = new TypeReference<>() {

    };

    /**
     * {@code chat_conversation.title} 上限。
     */
    private static final int CONV_TITLE_MAX = 256;

    /**
     * {@code chat_message.channel} 上限。
     */
    private static final int MSG_CHANNEL_MAX = 32;

    /**
     * {@code chat_message.trace_id} 上限。
     */
    private static final int MSG_TRACE_MAX = 64;

    /**
     * 节点 input/output_summary 上限。
     */
    private static final int NODE_SUMMARY_MAX = 1000;

    /**
     * 节点 error_code 上限。
     */
    private static final int NODE_ERROR_CODE_MAX = 64;

    /**
     * {@code task_instance.final_message} 上限。
     */
    private static final int TASK_FINAL_MESSAGE_MAX = 2000;

    /**
     * 任务实例表服务。
     */
    private final TaskInstanceService taskInstanceService;

    /**
     * 挂起检查点表服务。
     */
    private final TaskCheckpointService taskCheckpointService;

    /**
     * 会话主表服务。
     */
    private final ChatConversationService chatConversationService;

    /**
     * 交互消息表服务。
     */
    private final ChatMessageService chatMessageService;

    /**
     * 图节点表服务。
     */
    private final TaskGraphNodeService taskGraphNodeService;

    /**
     * 图边表服务。
     */
    private final TaskGraphEdgeService taskGraphEdgeService;

    /**
     * 挂起恢复审计表服务。
     */
    private final TaskResumeRecordService taskResumeRecordService;

    /**
     * 工具调用记录表服务。
     */
    private final ToolCallRecordService toolCallRecordService;

    /**
     * 超大报文表。
     */
    private final TaskNodePayloadService taskNodePayloadService;

    /**
     * 轮末冷库事务：首句推进会话 {@code state_version}，再写任务 / 图节点空间 / 检查点 / 消息。
     * <p>CAS 影响 0 行则抛 {@link RuntimeErrorCode#STATE_FENCED}，整事务回滚。</p>
     *
     * @param scope 本轮视图
     */
    @Transactional(rollbackFor = Exception.class)
    public void persistTurn(DefaultTurnScope scope) {
        long stateVersion = scope.conversation().version();
        String conversationId = scope.conversation().conversationId();
        boolean terminal = scope.task().map(task -> TaskStatus.isTerminal(task.getStatus())).orElse(false);
        String activeTaskId = terminal ? null : scope.task().map(TaskMetadata::getTaskId).orElse(scope.conversation().activeTaskId());
        String sessionShared = toJson(scope.sessionShared());
        int advanced = chatConversationService.advanceStateVersion(conversationId, stateVersion, sessionShared, activeTaskId);
        if (advanced <= 0) {
            if (Objects.isNull(findConversation(conversationId))) {
                throw new BusinessException(RuntimeErrorCode.STATE_WRITE_FAILED);
            }
            throw new BusinessException(RuntimeErrorCode.STATE_FENCED);
        }

        scope.closedTasks().forEach(closed -> persistClosed(closed, scope, stateVersion));
        scope.task().ifPresent(task -> {
            TaskRecord record = null;
            if (terminal) {
                record = new TaskRecord(task.getTaskId(), Objects.toString(task.getAgentId(), ""),
                    task.getGoalSummary(), task.getStatus(), task.getFinalMessage(), scope.entities(), List.of(),
                    System.currentTimeMillis());
            }
            upsertTaskCold(task, scope.shared(), scope.prior().orElse(null), stateVersion, record);
            saveGraphCold(task.getTaskId(), scope.graph(), spacesOf(scope.nodes()));
            persistEvidenceHeads(scope.nodes());
            scope.lastResumeAction().ifPresent(action -> consumeCheckpoint(task.getTaskId(), action,
                scope.lastResumeMessageId().orElse("")));
            scope.checkpoint().ifPresent(checkpoint -> upsertCheckpoint(task.getTaskId(), checkpoint, task));
        });

        scope.pendingPersistDialogue().forEach(message -> {
            if (Objects.equals(message.getRole(), DialogueRole.USER)) {
                // 用户消息在 begin 时已 insert，避免轮末重复
                return;
            }
            insertMessage(conversationId, message.getTaskId(), message, null);
        });
    }

    /**
     * 轮末按已打开节点把证据头状态追到库表。
     *
     * @param nodes 本轮已打开节点
     */
    private void persistEvidenceHeads(Map<String, DefaultNodeScope> nodes) {
        nodes.forEach((_, node) -> node.evidence().forEach(head -> {
            ToolCallRecordVo existing = findCall(head.evidenceId());
            if (Objects.isNull(existing)) {
                return;
            }
            updateCall(head, existing.getResponse());
        }));
    }

    /**
     * 挂起：upsert {@code task_checkpoint}，并同步任务状态 SUSPENDED。
     *
     * @param taskId     任务 id
     * @param checkpoint 挂起检查点（问题、允许动作、待确认字段等）
     * @param metadata   任务元数据；可空。非空时带当前节点并同步状态
     */
    public void upsertCheckpoint(String taskId, TaskCheckpoint checkpoint, @Nullable TaskMetadata metadata) {
        try {
            // checkpoint 行 upsert，并把任务状态同步为 SUSPENDED
            TaskCheckpointVo existing = findCheckpoint(taskId);
            TaskCheckpointDto dto = Objects.nonNull(existing)
                ? TaskCheckpointConverter.INSTANCE.vo2dto(existing)
                : new TaskCheckpointDto();
            if (Objects.isNull(existing)) {
                dto.setCheckpointId(Strings.defaultIfBlank(checkpoint.getCheckpointId(), RuntimeUtils.uuid()));
            } else if (Strings.isNotBlank(checkpoint.getCheckpointId())) {
                dto.setCheckpointId(checkpoint.getCheckpointId());
            }

            dto.setTaskId(taskId);
            if (Objects.nonNull(metadata)) {
                dto.setNodeId(metadata.getCurrentNodeId());
            }
            dto.setType(checkpoint.getType().name());
            dto.setQuestion(checkpoint.getQuestion());
            dto.setExpectedFields(toJson(checkpoint.getExpectedFields()));
            dto.setAllowedActions(JSON.obj2str(actionNames(checkpoint.getAllowedActions())));
            dto.setSuggestions(toJson(checkpoint.getSuggestions()));
            dto.setPendingAction(toJson(checkpoint.getPendingAction()));
            taskCheckpointService.save(dto);

            syncTaskStatus(taskId, TaskStatus.SUSPENDED, metadata, null);
        } catch (Exception e) {
            log.warn("[RuntimeStatePersistence] upsertCheckpoint failed taskId={}", taskId, e);
            throw unavailable(e);
        }
    }

    /**
     * 从库读任务元数据：{@code task_instance} → {@link TaskMetadata}。
     *
     * @param taskId 任务 id
     * @return 任务元数据；没有行或状态为空为 {@code null}
     */
    public @Nullable TaskMetadata loadTaskMetadata(String taskId) {
        try {
            TaskInstanceVo vo = findTask(taskId);
            if (Objects.isNull(vo) || Strings.isBlank(vo.getStatus())) {
                return null;
            }
            TaskMetadata metadata = new TaskMetadata();
            metadata.setTaskId(vo.getTaskId());
            metadata.setConversationId(vo.getConversationId());
            metadata.setAgentId(vo.getAgentId());
            metadata.setGoalSummary(vo.getGoalSummary());
            metadata.setFinalMessage(vo.getFinalMessage());
            metadata.setCurrentNodeId(vo.getCurrentNodeId());
            metadata.setStatus(TaskStatus.valueOf(vo.getStatus()));
            metadata.setVersion(Objects.requireNonNullElse(vo.getStateVersion(), 0L));
            if (Objects.nonNull(vo.getSuspendedTime())) {
                metadata.setSuspendedAt(vo.getSuspendedTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            }
            return metadata;
        } catch (Exception e) {
            log.warn("[RuntimeStatePersistence] loadTaskMetadata failed taskId={}", taskId, e);
            throw unavailable(e);
        }
    }

    /**
     * 从库读检查点：{@code task_checkpoint} → {@link TaskCheckpoint}（挂起可保留至恢复）。
     *
     * @param taskId 任务 id
     * @return 检查点；没有行或类型为空为 {@code null}
     */
    public @Nullable TaskCheckpoint loadCheckpoint(String taskId) {
        try {
            TaskCheckpointVo vo = findCheckpoint(taskId);
            if (Objects.isNull(vo) || Strings.isBlank(vo.getType())) {
                return null;
            }
            TaskCheckpoint checkpoint = new TaskCheckpoint();
            checkpoint.setCheckpointId(vo.getCheckpointId());
            checkpoint.setType(CheckpointType.valueOf(vo.getType()));
            checkpoint.setQuestion(vo.getQuestion());
            checkpoint.setExpectedFields(parseStringList(vo.getExpectedFields()));
            checkpoint.setAllowedActions(parseResumeActions(vo.getAllowedActions()));
            checkpoint.setSuggestions(parseStringList(vo.getSuggestions()));
            if (Strings.isNotBlank(vo.getPendingAction())) {
                checkpoint.setPendingAction(JSON.str2obj(vo.getPendingAction(), PendingAction.class));
            }
            return checkpoint;
        } catch (Exception e) {
            log.warn("[RuntimeStatePersistence] loadCheckpoint failed taskId={}", taskId, e);
            throw unavailable(e);
        }
    }

    /**
     * 从库读执行图：{@code task_graph_node} + {@code task_graph_edge} → {@link TaskGraph}。
     * <p>无节点返回 {@code null}；边会回填各节点 dependsOn。</p>
     *
     * @param taskId 任务 id
     * @return 节点与边组成的执行图；无节点为 {@code null}
     */
    public @Nullable TaskGraph loadGraph(String taskId) {
        try {
            List<TaskGraphNodeVo> nodeVos = listNodes(taskId);
            if (Collections.isEmpty(nodeVos)) {
                return null;
            }

            Map<String, TaskNode> byId = new LinkedHashMap<>();
            for (TaskGraphNodeVo vo : nodeVos) {
                TaskNode node = new TaskNode();
                node.setNodeId(vo.getNodeId());
                node.setTitle(vo.getTitle());
                node.setObjective(vo.getObjective());
                node.setSkillKey(vo.getSkillKey());
                if (Objects.nonNull(vo.getDelegateDepth())) {
                    node.setDelegateDepth(vo.getDelegateDepth());
                }
                if (Strings.isNotBlank(vo.getStatus())) {
                    node.setStatus(NodeStatus.valueOf(vo.getStatus()));
                }
                node.setErrorCode(vo.getErrorCode());
                node.setErrorMessage(vo.getErrorMessage());
                byId.put(node.getNodeId(), node);
            }

            List<TaskEdge> edges = new ArrayList<>();
            for (TaskGraphEdgeVo vo : listEdges(taskId)) {
                EdgeType type = Strings.isBlank(vo.getEdgeType())
                    ? EdgeType.NORMAL
                    : EdgeType.valueOf(vo.getEdgeType());
                edges.add(new TaskEdge(vo.getFromNodeId(), vo.getToNodeId(), type));
                TaskNode to = byId.get(vo.getToNodeId());
                if (Objects.nonNull(to)) {
                    to.getDependsOn().add(vo.getFromNodeId());
                }
            }

            TaskGraph graph = new TaskGraph();
            graph.setNodes(new ArrayList<>(byId.values()));
            graph.setEdges(edges);
            return graph;
        } catch (Exception e) {
            log.warn("[RuntimeStatePersistence] loadGraph failed taskId={}", taskId, e);
            throw unavailable(e);
        }
    }

    /**
     * 解析挂起允许的恢复动作名列表。
     *
     * @param json 动作名 JSON 数组；可空
     * @return 枚举列表；空白为可写空列表
     */
    private static List<ResumeAction> parseResumeActions(@Nullable String json) {
        List<String> names = parseStringList(json);
        if (Collections.isEmpty(names)) {
            return new ArrayList<>();
        }
        List<ResumeAction> actions = new ArrayList<>(names.size());
        for (String name : names) {
            if (Strings.isNotBlank(name)) {
                actions.add(ResumeAction.valueOf(name));
            }
        }
        return actions;
    }

    /**
     * 解析字符串数组 JSON。
     *
     * @param json JSON 数组文本；可空
     * @return 字符串列表；空白为可写空列表
     */
    private static List<String> parseStringList(@Nullable String json) {
        if (Strings.isBlank(json)) {
            return new ArrayList<>();
        }

        return JSON.str2obj(json, STRING_LIST);
    }

    /**
     * 恢复成功：写 {@code task_resume_record}，再逻辑删除 {@code task_checkpoint}。
     *
     * @param taskId    任务 id
     * @param action    本次恢复动作
     * @param messageId 触发恢复的消息 id
     */
    public void consumeCheckpoint(String taskId, ResumeAction action, String messageId) {
        if (Strings.isBlank(taskId)) {
            return;
        }
        try {
            TaskCheckpointVo checkpoint = findCheckpoint(taskId);
            if (Objects.isNull(checkpoint)) {
                return;
            }
            TaskResumeRecordDto dto = new TaskResumeRecordDto();
            dto.setOrganId(PrincipalContextHolder.getOrganId());
            dto.setTaskId(taskId);
            dto.setNodeId(checkpoint.getNodeId());
            dto.setResumeId(RuntimeUtils.uuid());
            dto.setCheckpointId(checkpoint.getCheckpointId());
            dto.setMessageId(Strings.blankToNull(messageId));
            dto.setResumeAction(action.name());
            dto.setActedAt(Moments.now());
            taskResumeRecordService.save(dto);
            if (Objects.nonNull(checkpoint.getId())) {
                taskCheckpointService.delete(checkpoint.getId());
            }
        } catch (Exception e) {
            log.warn("[RuntimeStatePersistence] consumeCheckpoint failed taskId={} action={}", taskId, action, e);
            throw unavailable(e);
        }
    }

    /**
     * 刷新会话最后消息时间。
     *
     * @param conversationId 会话 id
     */
    private void touchConversation(String conversationId) {
        ChatConversationVo existing = findConversation(conversationId);
        if (Objects.isNull(existing)) {
            return;
        }

        var dto = ChatConversationConverter.INSTANCE.vo2dto(existing);
        dto.setLastMessageTime(Moments.now());
        chatConversationService.save(dto);
    }

    /**
     * 被顶替的旧任务：写终态行与图壳。
     *
     * @param closed       已关闭任务快照
     * @param scope        本轮视图
     * @param stateVersion 本轮栅栏版本
     */
    private void persistClosed(ClosedTask closed, DefaultTurnScope scope, long stateVersion) {
        TaskMetadata task = closed.metadata();
        TaskRecord record = new TaskRecord(task.getTaskId(), Objects.toString(task.getAgentId(), ""),
            task.getGoalSummary(), task.getStatus(), task.getFinalMessage(), scope.entities(), List.of(),
            System.currentTimeMillis());
        upsertTaskCold(task, closed.shared(), closed.prior(), stateVersion, record);
        saveGraphCold(task.getTaskId(), closed.graph(), closed.spaces());
    }

    /**
     * 轮末写任务行：已有行走 CAS；无行则 insert（{@code stateVersion} 取本轮栅栏）。
     *
     * @param metadata     任务元数据
     * @param shared       任务共享
     * @param prior        任务前情；可空
     * @param stateVersion 本轮栅栏版本
     * @param record       结束记录；进行中任务可空
     */
    private void upsertTaskCold(TaskMetadata metadata, Map<String, Sourced<?>> shared, @Nullable Prior prior,
                                long stateVersion, @Nullable TaskRecord record) {
        String taskId = Objects.requireNonNull(metadata.getTaskId());
        TaskInstanceVo existing = findTask(taskId);
        TaskInstanceDto dto = Objects.nonNull(existing)
            ? TaskInstanceConverter.INSTANCE.vo2dto(existing)
            : new TaskInstanceDto();
        if (Objects.isNull(existing)) {
            dto.setStartedTime(Moments.now());
        }
        dto.setConversationId(metadata.getConversationId());
        dto.setTaskId(taskId);
        dto.setAgentId(metadata.getAgentId());
        dto.setGoalSummary(metadata.getGoalSummary());
        dto.setStatus(Objects.requireNonNull(metadata.getStatus()).name());
        dto.setCurrentNodeId(metadata.getCurrentNodeId());
        dto.setFinalMessage(truncate(metadata.getFinalMessage(), TASK_FINAL_MESSAGE_MAX));
        dto.setStateVersion(stateVersion);
        dto.setShared(toJson(shared));
        dto.setPrior(Objects.isNull(prior) ? null : toJson(prior));
        dto.setRecord(toJson(record));
        if (Objects.nonNull(metadata.getSuspendedAt())) {
            dto.setSuspendedTime(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(metadata.getSuspendedAt()), ZoneId.systemDefault()
            ));
        }
        if (TaskStatus.isTerminal(metadata.getStatus())) {
            dto.setFinishedTime(Moments.now());
        }
        if (Objects.isNull(existing)) {
            taskInstanceService.save(dto);
            return;
        }
        if (taskInstanceService.advanceStateVersion(dto) <= 0) {
            throw new BusinessException(RuntimeErrorCode.STATE_FENCED);
        }
    }

    /**
     * 轮末写图：节点壳 + 已打开节点的 inputs / outputs / state。
     *
     * @param taskId 任务 id
     * @param graph  执行图
     * @param spaces 已打开节点空间
     */
    private void saveGraphCold(String taskId, TaskGraph graph, Map<String, NodeSpace> spaces) {
        saveNodes(taskId, graph.getNodes(), spaces);
        saveEdges(taskId, graph.getEdges());
    }

    /**
     * 把已打开节点视图收成节点空间。
     *
     * @param opened 本轮已打开节点
     * @return {@code nodeId →} 节点空间
     */
    private static Map<String, NodeSpace> spacesOf(Map<String, DefaultNodeScope> opened) {
        Map<String, NodeSpace> spaces = new LinkedHashMap<>();
        opened.forEach((id, node) -> spaces.put(id, new NodeSpace(
            node.inputs(), node.outputs(), node.state(), node.repair(), node.rounds(), node.tokens()
        )));
        return spaces;
    }

    /**
     * 按图节点 upsert {@code task_graph_node}。
     *
     * @param taskId 任务 id
     * @param nodes  图节点
     * @param spaces 已打开节点空间
     */
    private void saveNodes(String taskId, List<TaskNode> nodes, Map<String, NodeSpace> spaces) {
        var byNodeId = listNodes(taskId).stream().collect(Collectors.toMap(
            TaskGraphNodeVo::getNodeId, vo -> vo, (a, _) -> a
        ));

        for (TaskNode node : nodes) {
            TaskGraphNodeVo old = byNodeId.get(node.getNodeId());
            TaskGraphNodeDto dto;
            if (Objects.nonNull(old)) {
                dto = TaskGraphNodeConverter.INSTANCE.vo2dto(old);
                dto.setTitle(node.getTitle());
                dto.setObjective(node.getObjective());
                dto.setSkillKey(node.getSkillKey());
                dto.setStatus(node.getStatus().name());
                dto.setDelegateDepth(node.getDelegateDepth());
                dto.setErrorCode(node.getErrorCode());
                dto.setErrorMessage(node.getErrorMessage());
            } else {
                dto = TaskGraphNodeConverter.INSTANCE.node2dto(node);
                dto.setTaskId(taskId);
            }
            NodeSpace space = spaces.get(node.getNodeId());
            if (Objects.nonNull(space)) {
                dto.setInputs(toJson(space.inputs()));
                dto.setOutputs(toJson(space.outputs()));
                dto.setState(toJson(space));
            }
            taskGraphNodeService.save(dto);
        }
    }

    /**
     * 插入库中尚不存在的图边。
     *
     * @param taskId 任务 id
     * @param edges  图边
     */
    private void saveEdges(String taskId, List<TaskEdge> edges) {
        // 已落库边集合，避免重复 insert
        Set<String> knownKeys = listEdges(taskId).stream()
            .map(vo -> vo.getFromNodeId() + vo.getToNodeId())
            .collect(Collectors.toSet());

        for (TaskEdge edge : edges) {
            String key = edge.getFrom() + edge.getTo();
            if (knownKeys.contains(key)) {
                continue;
            }

            var dto = TaskGraphEdgeConverter.INSTANCE.edge2dto(edge);
            dto.setTaskId(taskId);
            taskGraphEdgeService.save(dto);
            knownKeys.add(key);
        }
    }

    /**
     * 把任务状态同步到 {@code task_instance}。
     *
     * @param taskId       任务 id
     * @param status       目标状态
     * @param metadata     可选元数据补丁；可空
     * @param finishedTime 结束时间；未结束为 {@code null}
     */
    @SuppressWarnings("SameParameterValue")
    private void syncTaskStatus(String taskId, TaskStatus status, @Nullable TaskMetadata metadata, @Nullable LocalDateTime finishedTime) {
        TaskInstanceVo existing = findTask(taskId);
        var dto = Objects.nonNull(existing) ? TaskInstanceConverter.INSTANCE.vo2dto(existing) : new TaskInstanceDto();

        if (Objects.nonNull(metadata)) {
            dto.setConversationId(metadata.getConversationId());
            dto.setAgentId(metadata.getAgentId());
            dto.setGoalSummary(metadata.getGoalSummary());
            dto.setCurrentNodeId(metadata.getCurrentNodeId());
            dto.setFinalMessage(truncate(metadata.getFinalMessage(), TASK_FINAL_MESSAGE_MAX));
            if (Objects.isNull(existing)) {
                dto.setStartedTime(Moments.now());
                dto.setStateVersion(0L);
            }
            if (Objects.nonNull(metadata.getSuspendedAt())) {
                dto.setSuspendedTime(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(metadata.getSuspendedAt()), ZoneId.systemDefault()
                ));
            }
        } else if (Objects.isNull(existing)) {
            dto.setStateVersion(0L);
        }

        dto.setTaskId(taskId);
        dto.setStatus(status.name());
        dto.setFinishedTime(finishedTime);
        taskInstanceService.save(dto);
    }

    /**
     * 按任务 id 读 {@code task_instance}。
     *
     * @param taskId 任务 id
     * @return 行；没有为 {@code null}
     */
    private @Nullable TaskInstanceVo findTask(String taskId) {
        TaskInstanceSelectDto query = new TaskInstanceSelectDto();
        query.setTaskId(taskId);
        List<TaskInstanceVo> list = taskInstanceService.selectList(query);
        return list.isEmpty() ? null : list.getFirst();
    }

    /**
     * 按任务 id 读 {@code task_checkpoint}。
     *
     * @param taskId 任务 id
     * @return 行；没有为 {@code null}
     */
    private @Nullable TaskCheckpointVo findCheckpoint(String taskId) {
        TaskCheckpointSelectDto query = new TaskCheckpointSelectDto();
        query.setTaskId(taskId);
        List<TaskCheckpointVo> list = taskCheckpointService.selectList(query);
        return list.isEmpty() ? null : list.getFirst();
    }

    /**
     * 按会话 id 读 {@code chat_conversation}。
     *
     * @param conversationId 会话 id
     * @return 行；没有为 {@code null}
     */
    private @Nullable ChatConversationVo findConversation(String conversationId) {
        ChatConversationSelectDto query = new ChatConversationSelectDto();
        query.setConversationId(conversationId);
        List<ChatConversationVo> list = chatConversationService.selectList(query);
        return list.isEmpty() ? null : list.getFirst();
    }

    /**
     * 列出任务图节点行。
     *
     * @param taskId 任务 id
     * @return 节点行；没有为空列表
     */
    private List<TaskGraphNodeVo> listNodes(String taskId) {
        TaskGraphNodeSelectDto query = new TaskGraphNodeSelectDto();
        query.setTaskId(taskId);
        return taskGraphNodeService.selectList(query);
    }

    /**
     * 列出任务图边行。
     *
     * @param taskId 任务 id
     * @return 边行；没有为空列表
     */
    private List<TaskGraphEdgeVo> listEdges(String taskId) {
        TaskGraphEdgeSelectDto query = new TaskGraphEdgeSelectDto();
        query.setTaskId(taskId);
        return taskGraphEdgeService.selectList(query);
    }

    /**
     * 按任务与节点 id 读图节点行。
     *
     * @param taskId 任务 id
     * @param nodeId 节点 id
     * @return 行；没有为 {@code null}
     */
    private @Nullable TaskGraphNodeVo findNode(String taskId, String nodeId) {
        return listNodes(taskId).stream()
            .filter(vo -> nodeId.equals(vo.getNodeId()))
            .findFirst()
            .orElse(null);
    }

    /**
     * 按会话 id 读主表行。
     *
     * @param conversationId 会话 id
     * @return 行；没有为 {@code null}
     */
    public @Nullable ChatConversationVo conversationOf(String conversationId) {
        return findConversation(conversationId);
    }

    /**
     * 从 {@code chat_conversation.session_shared} 回灌会话共享值。
     *
     * @param conversationId 会话 id
     * @return 会话记忆（已确认、可跨任务继承的字段）；没有为不可变空 Map
     */
    public Map<String, Sourced<?>> loadSessionShared(String conversationId) {
        ChatConversationVo row = findConversation(conversationId);
        if (Objects.isNull(row) || Strings.isBlank(row.getSessionShared())) {
            return Map.of();
        }

        return JSON.str2obj(row.getSessionShared(), SHARED_MAP);
    }

    /**
     * 从 {@code task_instance.shared} 回灌任务共享值。
     *
     * @param taskId 任务 id
     * @return 任务记忆（本任务按 path 读写的共享值）；没有为不可变空 Map
     */
    public Map<String, Sourced<?>> loadTaskShared(String taskId) {
        TaskInstanceVo row = findTask(taskId);
        if (Objects.isNull(row) || Strings.isBlank(row.getShared())) {
            return Map.of();
        }

        return JSON.str2obj(row.getShared(), SHARED_MAP);
    }

    /**
     * 从 {@code task_instance.prior} 回灌前情。
     *
     * @param taskId 任务 id
     * @return 任务前情（上一任务留给下一任务的衔接包）；没有为 {@code null}
     */
    public @Nullable Prior loadTaskPrior(String taskId) {
        TaskInstanceVo row = findTask(taskId);
        if (Objects.isNull(row) || Strings.isBlank(row.getPrior())) {
            return null;
        }
        return JSON.str2obj(row.getPrior(), Prior.class);
    }

    /**
     * 从 {@code task_graph_node} 三列回灌 NodeSpace。
     *
     * @param taskId 任务 id
     * @param nodeId 节点 id
     * @return 节点输入/输出/便签空间；没有行为 {@code null}
     */
    public @Nullable NodeSpace loadNodeSpace(String taskId, String nodeId) {
        TaskGraphNodeVo vo = findNode(taskId, nodeId);
        if (Objects.isNull(vo)) {
            return null;
        }
        if (Strings.isNotBlank(vo.getState())) {
            NodeSpace full = JSON.str2obj(vo.getState(), NodeSpace.class);
            if (Objects.nonNull(full)) {
                return full;
            }
        }
        Map<String, Sourced<?>> inputs = Strings.isBlank(vo.getInputs())
            ? Map.of()
            : Objects.requireNonNullElse(JSON.str2obj(vo.getInputs(), SOURCED_MAP), Map.of());
        Map<String, Sourced<?>> outputs = Strings.isBlank(vo.getOutputs())
            ? Map.of()
            : Objects.requireNonNullElse(JSON.str2obj(vo.getOutputs(), SOURCED_MAP), Map.of());
        Map<String, Object> state = Strings.isBlank(vo.getState())
            ? Map.of()
            : Objects.requireNonNullElse(JSON.str2obj(vo.getState(), STATE_MAP), Map.of());
        if (inputs.isEmpty() && outputs.isEmpty() && state.isEmpty()) {
            return null;
        }
        return new NodeSpace(inputs, outputs, state, null, 0, 0);
    }

    /**
     * 从已结束任务的 {@code task_instance.record} 回灌会话 records。
     *
     * @param conversationId 会话 id
     * @param limit          最多条数
     * @return 回合摘要（已结束任务留在会话上的短纪要）；没有为空列表
     */
    public List<TaskRecord> loadRecords(String conversationId, int limit) {
        TaskInstanceSelectDto query = new TaskInstanceSelectDto();
        query.setConversationId(conversationId);
        List<TaskInstanceVo> rows = taskInstanceService.selectList(query);
        if (Collections.isEmpty(rows)) {
            return List.of();
        }
        List<TaskRecord> records = new ArrayList<>();
        rows.stream()
            .filter(vo -> Strings.isNotBlank(vo.getRecord()))
            .sorted((a, b) -> {
                LocalDateTime fa = a.getFinishedTime();
                LocalDateTime fb = b.getFinishedTime();
                if (Objects.isNull(fa) && Objects.isNull(fb)) {
                    return 0;
                }
                if (Objects.isNull(fa)) {
                    return 1;
                }
                if (Objects.isNull(fb)) {
                    return -1;
                }
                return fb.compareTo(fa);
            })
            .limit(Math.max(0, limit))
            .forEach(vo -> {
                TaskRecord record = JSON.str2obj(vo.getRecord(), TaskRecord.class);
                if (Objects.nonNull(record)) {
                    records.add(record);
                }
            });
        return records;
    }

    /**
     * 新建会话行；已存在则跳过。失败向上抛。
     *
     * @param conversationId 会话 id
     * @param userId         所属用户
     * @param titleHint      可选标题
     */
    public void createConversation(String conversationId, Long userId, @Nullable String titleHint) {
        if (Objects.nonNull(findConversation(conversationId))) {
            return;
        }
        ChatConversationDto dto = new ChatConversationDto();
        dto.setConversationId(conversationId);
        dto.setOrganId(PrincipalContextHolder.getOrganId());
        dto.setUserId(userId);
        dto.setStateVersion(0L);
        if (Strings.isNotBlank(titleHint)) {
            dto.setTitle(truncate(titleHint.trim(), CONV_TITLE_MAX));
        }
        chatConversationService.save(dto);
    }

    /**
     * 从 {@code chat_message} 回灌会话对话（时间正序）。
     *
     * @param conversationId 会话 id
     * @param limit          最多条数
     * @return 消息
     */
    public List<DialogueMessage> loadDialogue(String conversationId, int limit) {
        ChatMessageSelectDto query = new ChatMessageSelectDto();
        query.setConversationId(conversationId);
        List<ChatMessageVo> rows = chatMessageService.selectList(query);
        if (Collections.isEmpty(rows)) {
            return List.of();
        }
        int from = Math.max(0, rows.size() - limit);
        return rows.subList(from, rows.size()).stream().map(this::toDialogue).toList();
    }

    /**
     * 写入一条对话；入口登记和轮末增量落库都会调用此方法，失败直接向上抛出。
     *
     * @param conversationId 会话 id
     * @param taskId         任务 id；可空
     * @param message        消息
     * @param channel        渠道
     * @return 落库 messageId
     */
    @SuppressWarnings("UnusedReturnValue")
    public String insertMessage(String conversationId, @Nullable String taskId, DialogueMessage message,
                                @Nullable String channel) {
        String messageId = Strings.isNotBlank(message.getMessageId()) ? message.getMessageId() : RuntimeUtils.uuid();
        ChatMessageDto dto = new ChatMessageDto();
        dto.setOrganId(PrincipalContextHolder.getOrganId());
        dto.setConversationId(conversationId);
        dto.setTaskId(taskId);
        dto.setNodeId(message.getNodeId());
        dto.setRound(message.getRound());
        dto.setMessageId(messageId);
        dto.setRole(Objects.requireNonNull(message.getRole()).name());
        MessageType type = Objects.nonNull(message.getType()) ? message.getType() : MessageType.from(message.getRole());
        dto.setMessageType(type.name());
        dto.setContent(message.getText());
        dto.setCreatedAtMs(message.getCreatedAt());
        if (Strings.isNotBlank(channel)) {
            dto.setChannel(truncate(channel.trim(), MSG_CHANNEL_MAX));
        }
        if (Strings.isNotBlank(message.getTraceId())) {
            dto.setTraceId(truncate(message.getTraceId(), MSG_TRACE_MAX));
        }
        chatMessageService.save(dto);
        touchConversation(conversationId);
        return messageId;
    }

    /**
     * 按 callId 查工具调用行。
     *
     * @param callId 证据 / 调用 id
     * @return 行；没有为 {@code null}
     */
    public @Nullable ToolCallRecordVo findCall(String callId) {
        ToolCallRecordSelectDto query = new ToolCallRecordSelectDto();
        query.setCallId(callId);
        List<ToolCallRecordVo> list = toolCallRecordService.selectList(query);
        return Collections.isEmpty(list) ? null : list.getFirst();
    }

    /**
     * Redis 证据 Hash 为空时，从 {@code tool_call_record} 回灌本节点摘要。
     *
     * @param taskId 任务 id
     * @param nodeId 节点 id
     * @return 证据头列表；参数空或没有行为空列表
     */
    public List<EvidenceHead> loadNodeEvidence(String taskId, String nodeId) {
        if (Strings.isBlank(taskId) || Strings.isBlank(nodeId)) {
            return List.of();
        }
        try {
            ToolCallRecordSelectDto query = new ToolCallRecordSelectDto();
            query.setTaskId(taskId);
            query.setNodeId(nodeId);
            List<ToolCallRecordVo> list = toolCallRecordService.selectList(query);
            if (Collections.isEmpty(list)) {
                return List.of();
            }
            List<EvidenceHead> heads = new ArrayList<>(list.size());
            for (ToolCallRecordVo vo : list) {
                EvidenceHead head = toHead(vo);
                if (Objects.nonNull(head)) {
                    heads.add(head);
                }
            }
            return heads;
        } catch (Exception e) {
            log.warn("[RuntimeStatePersistence] loadNodeEvidence failed taskId={} nodeId={}", taskId, nodeId, e);
            throw unavailable(e);
        }
    }

    /**
     * Redis writes 索引未命中时，按任务 + 工具 + 幂等键查有效写证据。
     *
     * @param taskId   任务 id
     * @param toolName 工具名
     * @param key      幂等键
     * @return 仍占用幂等槽的写证据；没有为空 Optional
     */
    public Optional<EvidenceHead> findLiveWrite(String taskId, String toolName, String key) {
        if (Strings.isBlank(taskId) || Strings.isBlank(toolName) || Strings.isBlank(key)) {
            return Optional.empty();
        }
        try {
            ToolCallRecordSelectDto query = new ToolCallRecordSelectDto();
            query.setTaskId(taskId);
            query.setToolName(toolName);
            query.setIdempotencyKey(key);
            List<ToolCallRecordVo> list = toolCallRecordService.selectList(query);
            if (Collections.isEmpty(list)) {
                return Optional.empty();
            }
            for (ToolCallRecordVo vo : list) {
                EvidenceHead head = toHead(vo);
                if (Objects.nonNull(head) && isLiveWrite(head)) {
                    return Optional.of(head);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[RuntimeStatePersistence] findLiveWrite failed taskId={} tool={}", taskId, toolName, e);
            throw unavailable(e);
        }
    }

    /**
     * 调用记录行转证据头。
     *
     * @param vo 表行
     * @return 证据头；缺 callId 为 {@code null}
     */
    private @Nullable EvidenceHead toHead(ToolCallRecordVo vo) {
        if (Strings.isBlank(vo.getCallId())) {
            return null;
        }
        EvidenceStatus status;
        try {
            status = Strings.isBlank(vo.getCallStatus()) ? EvidenceStatus.STARTED : EvidenceStatus.valueOf(vo.getCallStatus());
        } catch (IllegalArgumentException e) {
            status = EvidenceStatus.STARTED;
        }
        SideEffect sideEffect = SideEffect.NONE;
        if (Strings.isNotBlank(vo.getSideEffect())) {
            try {
                sideEffect = SideEffect.valueOf(vo.getSideEffect());
            } catch (IllegalArgumentException ignored) {

            }
        }
        FailureKind kind = FailureKind.NONE;
        if (Strings.isNotBlank(vo.getFailureKind())) {
            try {
                kind = FailureKind.valueOf(vo.getFailureKind());
            } catch (IllegalArgumentException ignored) {

            }
        }
        int round = Objects.isNull(vo.getRound()) ? 0 : vo.getRound();
        return new EvidenceHead(vo.getCallId(), vo.getToolName(), sideEffect, status, flag(vo.getBusinessOk()),
            flag(vo.getEmptyResult()), null, kind, vo.getIdempotencyKey(), vo.getArgsHash(), null,
            vo.getResultSummary(), vo.getErrorCode(), vo.getEntities(), vo.getPayloadId(), round,
            Objects.toString(vo.getTraceId(), ""));
    }

    /**
     * 字节标志转布尔。
     *
     * @param value 库字段；可空
     * @return {@code null} 保持空；非 0 为 {@code true}
     */
    private static @Nullable Boolean flag(@Nullable Byte value) {
        if (Objects.isNull(value)) {
            return null;
        }
        return value != 0;
    }

    /**
     * 写证据是否仍占用幂等槽。
     *
     * @param head 证据头
     * @return STARTED / UNKNOWN / 算成功的 SUCCESS 为 {@code true}
     */
    private static boolean isLiveWrite(EvidenceHead head) {
        EvidenceStatus status = head.status();
        if (status == EvidenceStatus.SUCCESS) {
            return !Boolean.FALSE.equals(head.businessOk());
        }
        return status == EvidenceStatus.STARTED
            || status == EvidenceStatus.UNKNOWN
            || status == EvidenceStatus.UNKNOWN_PRE_CALL;
    }

    /**
     * 为即将执行的工具调用插入 STARTED 记录；失败直接向上抛出。
     *
     * @param dto 已填必填字段的 DTO
     */
    public void insertStartedCall(ToolCallRecordDto dto) {
        toolCallRecordService.save(dto);
    }

    /**
     * 更新调用状态与可选响应。
     *
     * @param callId    调用 id
     * @param status    新状态
     * @param response  响应 JSON；可空
     * @param summary   结果摘要；可空
     * @param errorCode 错误码；可空
     */
    public void updateCall(String callId, String status, @Nullable String response, @Nullable String summary, @Nullable String errorCode) {
        ToolCallRecordVo existing = findCall(callId);
        if (Objects.isNull(existing)) {
            return;
        }
        ToolCallRecordDto dto = ToolCallRecordConverter.INSTANCE.vo2dto(existing);
        dto.setCallStatus(status);
        dto.setResponse(response);
        dto.setResultSummary(truncate(summary, NODE_SUMMARY_MAX));
        dto.setErrorCode(truncate(errorCode, NODE_ERROR_CODE_MAX));
        toolCallRecordService.save(dto);
    }

    /**
     * 按证据摘要更新调用行（含业务语义字段）。
     *
     * @param head     证据头（状态、摘要、业务成败等）
     * @param response 响应 JSON；可空
     */
    public void updateCall(EvidenceHead head, @Nullable String response) {
        ToolCallRecordVo existing = findCall(head.evidenceId());
        if (Objects.isNull(existing)) {
            return;
        }
        ToolCallRecordDto dto = ToolCallRecordConverter.INSTANCE.vo2dto(existing);
        dto.setCallStatus(head.status().name());
        dto.setResponse(response);
        dto.setResultSummary(truncate(head.resultBrief(), NODE_SUMMARY_MAX));
        dto.setErrorCode(truncate(head.errorCode(), NODE_ERROR_CODE_MAX));
        if (Objects.nonNull(head.businessOk())) {
            dto.setBusinessOk(head.businessOk() ? (byte) 1 : (byte) 0);
        }
        dto.setEmptyResult(Boolean.TRUE.equals(head.empty()) ? (byte) 1 : (byte) 0);
        FailureKind kind = head.failureKind();
        dto.setFailureKind(kind.name());
        dto.setEntities(head.entities());
        dto.setPayloadId(head.payloadId());
        toolCallRecordService.save(dto);
    }

    /**
     * 超限证据报文写入 {@code task_node_payload}（ARTIFACT）。
     *
     * @param payloadId   与 evidenceId 相同
     * @param taskId      任务 id
     * @param nodeId      节点 id
     * @param toolName    工具名；可空
     * @param traceId     本轮 trace；可空
     * @param payloadData 完整报文
     */
    public void saveArtifact(String payloadId, String taskId, String nodeId, @Nullable String toolName,
                             @Nullable String traceId, String payloadData) {
        savePayload(payloadId, taskId, nodeId, PayloadType.ARTIFACT, toolName, traceId, payloadData);
    }

    /**
     * 超长 NodeSpace Hash 字段写入 {@code task_node_payload}（OUTPUT）。
     *
     * @param payloadId   新 id
     * @param taskId      任务 id
     * @param nodeId      节点 id
     * @param field       Hash field 名（inputs / outputs / state / repair）
     * @param traceId     本轮 trace；可空
     * @param payloadData 字段完整 JSON
     */
    public void saveSpaceField(String payloadId, String taskId, String nodeId, String field,
                               @Nullable String traceId, String payloadData) {
        savePayload(payloadId, taskId, nodeId, PayloadType.OUTPUT, field, traceId, payloadData);
    }

    /**
     * 读 OUTPUT 行的字段 JSON。
     *
     * @param payloadId 占位 id
     * @return 字段 JSON；没有则为 {@code null}
     */
    public @Nullable String loadSpaceField(String payloadId) {
        return loadPayload(payloadId, PayloadType.OUTPUT);
    }

    /**
     * 先读 {@code tool_call_record.response}，没有再读 ARTIFACT 行。
     *
     * @param evidenceId 证据 id
     * @return 报文 JSON；没有则为 {@code null}
     */
    public @Nullable String loadEvidenceBody(String evidenceId) {
        ToolCallRecordVo row = findCall(evidenceId);
        if (Objects.nonNull(row) && Strings.isNotBlank(row.getResponse())) {
            return row.getResponse();
        }
        return loadPayload(evidenceId, PayloadType.ARTIFACT);
    }

    /**
     * 写 {@code task_node_payload} 一行。
     *
     * @param payloadId   报文 id
     * @param taskId      任务 id
     * @param nodeId      节点 id
     * @param type        报文类型
     * @param toolName    工具名；可空
     * @param traceId     追踪 id；可空
     * @param payloadData 报文体
     */
    private void savePayload(String payloadId, String taskId, String nodeId, PayloadType type,
                             @Nullable String toolName, @Nullable String traceId, String payloadData) {
        TaskNodePayloadDto dto = new TaskNodePayloadDto();
        dto.setOrganId(PrincipalContextHolder.getOrganId());
        dto.setPayloadId(payloadId);
        dto.setTaskId(taskId);
        dto.setNodeId(nodeId);
        dto.setPayloadType(type.name());
        dto.setToolName(toolName);
        dto.setTraceId(traceId);
        dto.setPayloadData(payloadData);
        taskNodePayloadService.save(dto);
    }

    /**
     * 按 payloadId + 类型读一行。
     *
     * @param payloadId 报文 id
     * @param type      报文类型
     * @return 报文体；没有为 {@code null}
     */
    private @Nullable String loadPayload(String payloadId, PayloadType type) {
        TaskNodePayloadSelectDto query = new TaskNodePayloadSelectDto();
        query.setPayloadId(payloadId);
        query.setPayloadType(type.name());
        List<TaskNodePayloadVo> list = taskNodePayloadService.selectList(query);
        return Collections.isEmpty(list) ? null : list.getFirst().getPayloadData();
    }

    /**
     * {@code chat_message} 行转对话消息。
     *
     * @param vo 表行
     * @return 对话消息
     */
    private DialogueMessage toDialogue(ChatMessageVo vo) {
        DialogueMessage message = new DialogueMessage();
        if (Strings.isNotBlank(vo.getRole())) {
            message.setRole(DialogueRole.valueOf(vo.getRole()));
        }
        message.setText(vo.getContent());
        message.setMessageId(vo.getMessageId());
        message.setTraceId(vo.getTraceId());
        if (Strings.isNotBlank(vo.getMessageType())) {
            message.setType(MessageType.valueOf(vo.getMessageType()));
        }
        message.setTaskId(vo.getTaskId());
        message.setNodeId(vo.getNodeId());
        message.setRound(vo.getRound());
        message.setCreatedAt(Objects.requireNonNullElse(vo.getCreatedAtMs(), 0L));
        return message;
    }

    /**
     * 按库字段上限截断；空入参原样返回。
     *
     * @param value 原文；可空
     * @param max   最大字符数
     * @return 截断结果；{@code null} 入参仍为 {@code null}
     */
    private static @Nullable String truncate(@Nullable String value, int max) {
        if (Objects.isNull(value) || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /**
     * 恢复动作枚举转名字列表。
     *
     * @param actions 恢复动作
     * @return 名字列表；空入参为空列表
     */
    private static List<String> actionNames(List<ResumeAction> actions) {
        if (Collections.isEmpty(actions)) {
            return List.of();
        }

        return actions.stream().map(Enum::name).toList();
    }

    /**
     * 对象转 JSON 文本。
     *
     * @param value 对象；可空
     * @return JSON；{@code null} 入参为 {@code null}
     */
    private static @Nullable String toJson(@Nullable Object value) {
        return Objects.isNull(value) ? null : JSON.obj2str(value);
    }

    /**
     * 把持久化异常收成状态不可用。
     *
     * @param e 原始异常
     * @return 业务异常；已是 {@link BusinessException} 则原样返回
     */
    private static BusinessException unavailable(Exception e) {
        if (e instanceof BusinessException business) {
            return business;
        }
        return new BusinessException(RuntimeErrorCode.STATE_UNAVAILABLE);
    }
}
