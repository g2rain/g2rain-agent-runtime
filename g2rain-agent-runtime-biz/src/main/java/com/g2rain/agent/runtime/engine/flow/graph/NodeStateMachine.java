package com.g2rain.agent.runtime.engine.flow.graph;


import com.g2rain.agent.runtime.domain.graph.NodeEvent;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;

/**
 * NodeStateMachine（节点状态机）：按 {@link NodeEvent} 把节点从当前态迁到下一态。
 * <p>
 * 同时给出是否应停图的提示（挂起/失败/取消等通常停图）。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface NodeStateMachine {

    /**
     * 计算一次状态迁移。
     *
     * @param event 触发事件
     * @param from  当前节点状态
     * @return 迁移结果（目标态、是否停图等）
     */
    NodeTransition transit(NodeEvent event, NodeStatus from);
}
