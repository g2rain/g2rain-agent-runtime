package com.g2rain.agent.runtime.engine.state.repo;

import com.g2rain.agent.runtime.converter.TaskCheckpointConverter;
import com.g2rain.agent.runtime.converter.TaskInstanceConverter;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.dto.TaskCheckpointDto;
import com.g2rain.agent.runtime.dto.TaskCheckpointSelectDto;
import com.g2rain.agent.runtime.dto.TaskInstanceDto;
import com.g2rain.agent.runtime.dto.TaskInstanceSelectDto;
import com.g2rain.agent.runtime.dto.TaskResumeRecordDto;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.service.TaskCheckpointService;
import com.g2rain.agent.runtime.service.TaskInstanceService;
import com.g2rain.agent.runtime.service.TaskResumeRecordService;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.vo.TaskCheckpointVo;
import com.g2rain.agent.runtime.vo.TaskInstanceVo;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Moments;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 任务仓储：{@code task_instance}、{@code task_checkpoint}、{@code task_resume_record} 的读写。
 *
 * @author alpha
 * @since 2026/10/12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRepository {

    /**
     * 反序列化字符串列表的类型引用。
     */
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {

    };

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
     * 挂起恢复审计表服务。
     */
    private final TaskResumeRecordService taskResumeRecordService;

    /**
     * 从库读任务元数据：{@code task_instance} → {@link TaskMetadata}。
     *
     * @param taskId 任务 id
     * @return 任务元数据；没有行或状态为空为 {@code null}
     */
    public @Nullable TaskMetadata loadMetadata(String taskId) {
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
            metadata.setRequest(vo.getRequest());
            metadata.setFinalMessage(vo.getFinalMessage());
            metadata.setCurrentNodeId(vo.getCurrentNodeId());
            metadata.setStatus(TaskStatus.valueOf(vo.getStatus()));
            metadata.setVersion(Objects.requireNonNullElse(vo.getStateVersion(), 0L));
            if (Objects.nonNull(vo.getSuspendedTime())) {
                metadata.setSuspendedAt(RuntimeUtils.epochOf(vo.getSuspendedTime()));
            }
            if (Objects.nonNull(vo.getStartedTime())) {
                metadata.setStartedAt(RuntimeUtils.epochOf(vo.getStartedTime()));
            }
            metadata.setTraceId(vo.getTraceId());
            metadata.setSourceMessageId(vo.getSourceMessageId());
            return metadata;
        } catch (Exception e) {
            log.warn("[TaskRepository] loadMetadata failed taskId={}", taskId, e);
            throw RepoSupport.unavailable(e);
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
                checkpoint.setPendingAction(RepoSupport.JSON.str2obj(vo.getPendingAction(), PendingAction.class));
            }
            return checkpoint;
        } catch (Exception e) {
            log.warn("[TaskRepository] loadCheckpoint failed taskId={}", taskId, e);
            throw RepoSupport.unavailable(e);
        }
    }

    /**
     * 从 {@code task_instance.shared} 回灌任务共享值。
     *
     * @param taskId 任务 id
     * @return 任务记忆（本任务按 path 读写的共享值）；没有为不可变空 Map
     */
    public Map<String, Sourced<?>> loadShared(String taskId) {
        TaskInstanceVo row = findTask(taskId);
        if (Objects.isNull(row) || Strings.isBlank(row.getShared())) {
            return Map.of();
        }

        return RepoSupport.JSON.str2obj(row.getShared(), RepoSupport.SHARED_MAP);
    }

    /**
     * 从 {@code task_instance.prior} 回灌前情。
     *
     * @param taskId 任务 id
     * @return 任务前情（上一任务留给下一任务的衔接包）；没有为 {@code null}
     */
    public @Nullable Prior loadPrior(String taskId) {
        TaskInstanceVo row = findTask(taskId);
        if (Objects.isNull(row) || Strings.isBlank(row.getPrior())) {
            return null;
        }
        return RepoSupport.JSON.str2obj(row.getPrior(), Prior.class);
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
        // 只取有纪要的任务，按结束时间倒序（未结束排后）截取，再反序列化
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
                TaskRecord record = RepoSupport.JSON.str2obj(vo.getRecord(), TaskRecord.class);
                if (Objects.nonNull(record)) {
                    records.add(record);
                }
            });
        return records;
    }

    /**
     * 轮末写任务行：已有行走 CAS；无行则 insert（{@code stateVersion} 取本轮栅栏）。
     *
     * @param metadata     任务元数据
     * @param shared       任务共享
     * @param prior        任务前情；可空
     * @param stateVersion 本轮栅栏版本
     * @param record       结束记录；进行中任务可空
     * @return 本次新建了任务行
     */
    public boolean save(TaskMetadata metadata, Map<String, Sourced<?>> shared, @Nullable Prior prior,
                        long stateVersion, @Nullable TaskRecord record) {
        String taskId = Objects.requireNonNull(metadata.getTaskId());
        TaskInstanceVo existing = findTask(taskId);
        TaskInstanceDto dto = Objects.nonNull(existing)
            ? TaskInstanceConverter.INSTANCE.vo2dto(existing)
            : new TaskInstanceDto();
        if (Objects.isNull(existing)) {
            applyOrigin(dto, metadata);
        }
        dto.setConversationId(metadata.getConversationId());
        dto.setTaskId(taskId);
        dto.setAgentId(metadata.getAgentId());
        dto.setGoalSummary(metadata.getGoalSummary());
        dto.setRequest(metadata.getRequest());
        dto.setStatus(Objects.requireNonNull(metadata.getStatus()).name());
        dto.setCurrentNodeId(metadata.getCurrentNodeId());
        dto.setFinalMessage(RuntimeUtils.clip(metadata.getFinalMessage(), TASK_FINAL_MESSAGE_MAX));
        dto.setStateVersion(stateVersion);
        dto.setShared(RepoSupport.toJson(shared));
        dto.setPrior(Objects.isNull(prior) ? null : RepoSupport.toJson(prior));
        dto.setRecord(RepoSupport.toJson(record));
        if (Objects.nonNull(metadata.getSuspendedAt())) {
            dto.setSuspendedTime(RuntimeUtils.timeOf(metadata.getSuspendedAt()));
        }
        if (TaskStatus.isTerminal(metadata.getStatus())) {
            dto.setFinishedTime(Moments.now());
        }
        if (Objects.isNull(existing)) {
            taskInstanceService.save(dto);
            return true;
        }
        if (taskInstanceService.advanceStateVersion(dto) <= 0) {
            throw new BusinessException(RuntimeErrorCode.STATE_FENCED);
        }
        return false;
    }

    /**
     * 挂起：upsert {@code task_checkpoint}，并同步任务状态 SUSPENDED。
     *
     * @param taskId     任务 id
     * @param checkpoint 挂起检查点（问题、允许动作、待确认字段等）
     * @param metadata   任务元数据；可空。非空时带当前节点并同步状态
     * @return 本次新建了任务行
     */
    public boolean suspend(String taskId, TaskCheckpoint checkpoint, @Nullable TaskMetadata metadata) {
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
            dto.setExpectedFields(RepoSupport.toJson(checkpoint.getExpectedFields()));
            dto.setAllowedActions(RepoSupport.JSON.obj2str(actionNames(checkpoint.getAllowedActions())));
            dto.setSuggestions(RepoSupport.toJson(checkpoint.getSuggestions()));
            dto.setPendingAction(RepoSupport.toJson(checkpoint.getPendingAction()));
            taskCheckpointService.save(dto);

            return syncSuspended(taskId, metadata);
        } catch (Exception e) {
            log.warn("[TaskRepository] suspend failed taskId={}", taskId, e);
            throw RepoSupport.unavailable(e);
        }
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
            log.warn("[TaskRepository] consumeCheckpoint failed taskId={} action={}", taskId, action, e);
            throw RepoSupport.unavailable(e);
        }
    }

    /**
     * 把任务挂起状态同步到 {@code task_instance}。
     *
     * @param taskId   任务 id
     * @param metadata 可选元数据补丁；可空
     * @return 本次新建了任务行
     */
    private boolean syncSuspended(String taskId, @Nullable TaskMetadata metadata) {
        TaskInstanceVo existing = findTask(taskId);
        var dto = Objects.nonNull(existing) ? TaskInstanceConverter.INSTANCE.vo2dto(existing) : new TaskInstanceDto();

        if (Objects.nonNull(metadata)) {
            dto.setConversationId(metadata.getConversationId());
            dto.setAgentId(metadata.getAgentId());
            dto.setGoalSummary(metadata.getGoalSummary());
            dto.setRequest(metadata.getRequest());
            dto.setCurrentNodeId(metadata.getCurrentNodeId());
            dto.setFinalMessage(RuntimeUtils.clip(metadata.getFinalMessage(), TASK_FINAL_MESSAGE_MAX));
            if (Objects.isNull(existing)) {
                applyOrigin(dto, metadata);
                dto.setStateVersion(0L);
            }
            if (Objects.nonNull(metadata.getSuspendedAt())) {
                dto.setSuspendedTime(RuntimeUtils.timeOf(metadata.getSuspendedAt()));
            }
        } else if (Objects.isNull(existing)) {
            dto.setStateVersion(0L);
        }

        dto.setTaskId(taskId);
        dto.setStatus(TaskStatus.SUSPENDED.name());
        dto.setFinishedTime(null);
        taskInstanceService.save(dto);
        return Objects.isNull(existing);
    }

    /**
     * 新任务行的来源字段：开启时间、开启那一轮的 traceId、触发的用户消息 id。
     *
     * <p>开启时间取任务开启时记录的时刻，没有记录才用当前时间。</p>
     *
     * @param dto      待插入的任务行
     * @param metadata 任务元数据
     */
    private static void applyOrigin(TaskInstanceDto dto, TaskMetadata metadata) {
        Long startedAt = metadata.getStartedAt();
        dto.setStartedTime(Objects.isNull(startedAt) ? Moments.now() : RuntimeUtils.timeOf(startedAt));
        dto.setTraceId(RuntimeUtils.clip(metadata.getTraceId(), RepoSupport.TRACE_MAX));
        dto.setSourceMessageId(metadata.getSourceMessageId());
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

        return RepoSupport.JSON.str2obj(json, STRING_LIST);
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
}
