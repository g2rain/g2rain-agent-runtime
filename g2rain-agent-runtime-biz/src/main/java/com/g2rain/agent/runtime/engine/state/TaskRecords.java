package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.support.RuntimeUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 任务记录构造：任务结束时留在会话上的纪要，作为后续任务的前情。
 *
 * @author alpha
 * @since 2026/11/7
 */
final class TaskRecords {

    /**
     * 用户原文保留上限（字符）。
     */
    private static final int REQUEST_MAX_CHARS = 300;

    /**
     * 禁止实例化：只提供静态构造。
     */
    private TaskRecords() {

    }

    /**
     * 按任务元数据与本轮视图生成任务记录。
     *
     * @param task  已结束任务
     * @param scope 本轮视图
     * @return 任务记录
     */
    static TaskRecord of(TaskMetadata task, DefaultTurnScope scope) {
        return new TaskRecord(task.getTaskId(), Objects.toString(task.getAgentId(), ""), task.getGoalSummary(),
            task.getStatus(), task.getFinalMessage(), RuntimeUtils.clip(task.getRequest(), REQUEST_MAX_CHARS),
            task.getRejected(), task.getGoalMet(), entityKeys(task.getTaskId(), scope), List.of(),
            System.currentTimeMillis());
    }

    /**
     * 本任务涉及的实体键：实体上记的最近任务即为本任务。
     *
     * @param taskId 任务 id
     * @param scope  本轮视图
     * @return 实体键列表
     */
    private static List<String> entityKeys(String taskId, DefaultTurnScope scope) {
        return scope.entities().entrySet().stream()
            .filter(entry -> Objects.equals(taskId, entry.getValue().taskId()))
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    }
}
