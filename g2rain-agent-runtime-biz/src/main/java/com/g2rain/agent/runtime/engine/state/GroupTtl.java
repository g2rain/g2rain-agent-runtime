package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.store.KeyTtl;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 任务组 TTL：按粗状态选时长，并为组内 key 生成续期列表。
 *
 * @author alpha
 * @since 2026/11/22
 */
@Component
@RequiredArgsConstructor
public class GroupTtl {

    /**
     * 任务组 TTL 与哨兵间隔配置。
     */
    private final RuntimeProperties properties;

    /**
     * 按任务粗状态选 TTL：{@code SUSPENDED} 用 {@code task-ttl-suspended}，其余用 {@code task-ttl-running}。
     *
     * @param task 活跃任务；无任务为空
     * @return TTL
     */
    public Duration of(@Nullable TaskMetadata task) {
        if (Objects.nonNull(task) && Objects.equals(task.getStatus(), TaskStatus.SUSPENDED)) {
            return properties.getState().getTaskTtlSuspended();
        }
        return properties.getState().getTaskTtlRunning();
    }

    /**
     * 任务组全部 key 的续期：元数据为 {@code TTL − sentinel-gap}（下限 1ms），其余为 TTL。
     *
     * @param keys 会话 key
     * @param turn 本轮
     * @param ttl  任务 TTL
     * @return 续期列表；无任务为空列表
     */
    public List<KeyTtl> renewals(SessionKeys keys, DefaultTurnScope turn, Duration ttl) {
        return turn.task().map(meta -> {
            String taskId = meta.getTaskId();
            long taskMs = ttl.toMillis();
            long sentinel = Math.max(1L, taskMs - properties.getState().getSentinelGap().toMillis());
            List<String> groupKeys = keys.group(taskId, turn.graph());
            List<KeyTtl> extras = new ArrayList<>(groupKeys.size());
            for (String key : groupKeys) {
                long ttlMs = Objects.equals(key, keys.metadata(taskId)) ? sentinel : taskMs;
                extras.add(new KeyTtl(key, ttlMs));
            }
            return extras;
        }).orElse(List.of());
    }
}
