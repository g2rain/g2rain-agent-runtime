package com.g2rain.agent.runtime.engine.flow.graph;


import com.g2rain.agent.runtime.domain.graph.NodeStatus;

import java.util.List;

/**
 * 节点状态迁移结果：一次状态机计算的目标态与是否停图。
 *
 * @param from      迁移前状态
 * @param to        迁移后状态
 * @param haltGraph 是否应停图（挂起、失败、取消通常为 true）
 * @param effects   伴随的副作用；默认可为空
 * @author alpha
 * @since 2026/8/21
 */
public record NodeTransition(NodeStatus from, NodeStatus to, boolean haltGraph, List<GraphEffect> effects) {

    /**
     * 无伴随副作用的迁移。
     *
     * @param from      迁移前状态
     * @param to        迁移后状态
     * @param haltGraph 是否应停图
     */
    public NodeTransition(NodeStatus from, NodeStatus to, boolean haltGraph) {
        this(from, to, haltGraph, List.of());
    }
}
