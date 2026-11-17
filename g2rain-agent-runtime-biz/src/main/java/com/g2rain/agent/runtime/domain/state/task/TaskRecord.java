package com.g2rain.agent.runtime.domain.state.task;


import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 回合摘要：已结束 Task 留在会话上的短纪要。
 *
 * <p>保留目标、终态、摘要、本任务涉及的实体键和证据索引，供后续指代与审计；
 * 不参与活跃任务调度，也不可当作当前任务的可写状态。</p>
 *
 * @param taskId     任务 id
 * @param agentId    Agent id
 * @param goal       目标简述
 * @param status     终态
 * @param summary    finalMessage 前 500 字
 * @param request    开任务时的用户原文（截断）；摘要会丢失的细节由此还原
 * @param rejected   以拒绝结束时被拒绝的待批动作；否则为空
 * @param goalMet    目标核验结论；未核验为空
 * @param entityKeys 本任务涉及的实体键（{@code type:id}），实体内容在会话实体索引中
 * @param artifacts  {taskId, evidenceId} 列表
 * @param closedAt   结束时间毫秒
 * @author alpha
 * @since 2026/10/3
 */
public record TaskRecord(String taskId, String agentId, @Nullable String goal, TaskStatus status,
                         @Nullable String summary, @Nullable String request, @Nullable RejectedAction rejected,
                         @Nullable Boolean goalMet, List<String> entityKeys,
                         List<Map<String, String>> artifacts, long closedAt) {

    /**
     * 规范化集合字段，防止调用方在记录创建后继续修改历史快照。
     */
    public TaskRecord {
        entityKeys = List.copyOf(entityKeys);
        artifacts = List.copyOf(artifacts);
    }
}
