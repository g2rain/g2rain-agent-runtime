package com.g2rain.agent.runtime.domain.state.task;


import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 回合摘要：已结束 Task 留在会话上的短纪要。
 *
 * <p>保留目标、终态、摘要、会话记忆实体和证据索引，供后续指代与审计；
 * 不参与活跃任务调度，也不可当作当前任务的可写状态。</p>
 *
 * @param taskId    任务 id
 * @param agentId   Agent id
 * @param goal      目标简述
 * @param status    终态
 * @param summary   finalMessage 前 500 字
 * @param entities  本任务抽出的会话记忆实体
 * @param artifacts {taskId, evidenceId} 列表
 * @param closedAt  结束时间毫秒
 * @author alpha
 * @since 2026/10/3
 */
public record TaskRecord(String taskId, String agentId, @Nullable String goal, TaskStatus status,
                         @Nullable String summary, Map<String, EntityEntry> entities,
                         List<Map<String, String>> artifacts, long closedAt) {

    /**
     * 规范化集合字段，防止调用方在记录创建后继续修改历史快照。
     */
    public TaskRecord {
        entities = Map.copyOf(entities);
        artifacts = List.copyOf(artifacts);
    }
}
