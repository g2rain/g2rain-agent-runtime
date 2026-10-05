package com.g2rain.agent.runtime.domain.state.task;


import java.util.List;
import java.util.Map;

/**
 * 任务前情：上一 Task 留给下一 Task 的可过期衔接包。
 *
 * <p>开新任务时生成，此后只读。含近期回合摘要、按指代匹配的会话记忆实体与证据引用，
 * 供 {@link com.g2rain.agent.runtime.domain.decision.TurnMode#START_WITH_CONTEXT} 继承；
 * 不参与当前任务图调度。</p>
 *
 * @param tasks     近期已结束任务的回合摘要
 * @param entities  按指代匹配的会话记忆实体
 * @param artifacts 产物引用（taskId + evidenceId）
 * @param reason    生成说明
 * @author alpha
 * @since 2026/10/3
 */
public record Prior(List<TaskRecord> tasks, Map<String, EntityEntry> entities, List<Map<String, String>> artifacts,
                    String reason) {

    public Prior {
        tasks = List.copyOf(tasks);
        entities = Map.copyOf(entities);
        artifacts = List.copyOf(artifacts);
    }
}
