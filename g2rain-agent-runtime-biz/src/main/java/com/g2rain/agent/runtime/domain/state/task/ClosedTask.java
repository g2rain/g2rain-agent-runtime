package com.g2rain.agent.runtime.domain.state.task;


import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.state.Sourced;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 本轮被关掉、已被新任务顶替的旧任务快照，供 persist 一起落库。
 *
 * <p>换题或换 Agent 时旧任务已 close，但本轮 flush 尚未提交；把元数据、图、共享值、
 * 任务前情和节点空间打成一份，避免只落新任务丢旧终态。</p>
 *
 * @param metadata   终态元数据
 * @param graph      关闭时的任务图
 * @param shared     关闭时的任务共享值
 * @param prior      关闭时的任务前情；可空
 * @param payloadIds 关闭时已登记的报文 id
 * @param spaces     关闭时已打开节点的空间
 * @author alpha
 * @since 2026/10/4
 */
public record ClosedTask(TaskMetadata metadata, TaskGraph graph, Map<String, Sourced<?>> shared,
                         @Nullable Prior prior, List<String> payloadIds, Map<String, NodeSpace> spaces) {

    public ClosedTask {
        shared = Map.copyOf(shared);
        payloadIds = List.copyOf(payloadIds);
        spaces = Map.copyOf(spaces);
    }
}
