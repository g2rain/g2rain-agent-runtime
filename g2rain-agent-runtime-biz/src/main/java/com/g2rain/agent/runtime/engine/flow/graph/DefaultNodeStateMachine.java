package com.g2rain.agent.runtime.engine.flow.graph;


import com.g2rain.agent.runtime.domain.graph.NodeEvent;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import org.springframework.stereotype.Component;

/**
 * DefaultNodeStateMachine：默认节点状态机。
 * <p>
 * 把选中/循环结果/用户拒绝或取消等事件映射到节点态，并标明是否应停图。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Component
public class DefaultNodeStateMachine implements NodeStateMachine {

    /**
     * 按事件计算迁移：目标态 + 是否应停图。
     *
     * @param event 触发事件
     * @param from  当前节点状态
     * @return 迁移结果
     */
    @Override
    public NodeTransition transit(NodeEvent event, NodeStatus from) {
        return switch (event) {
            // 调度选中本节点：进入节点内循环（RUNNING）；不停图
            case SELECTED -> new NodeTransition(from, NodeStatus.RUNNING, false);

            // 节点内循环需要等人：挂起；应停图，等下轮续跑
            case LOOP_SUSPENDED -> new NodeTransition(from, NodeStatus.SUSPENDED, true);

            // 节点内循环委派子目标：父等待子；不停图，本拍继续调度子节点
            case LOOP_WAITING_DELEGATE -> new NodeTransition(from, NodeStatus.WAITING_DELEGATE, false);

            // 节点内循环正常完成：节点 DONE；可继续调度下游，不停图
            case LOOP_DONE -> new NodeTransition(from, NodeStatus.DONE, false);

            // 节点内循环失败或循环超时：节点 FAILED；应停图
            case LOOP_FAILED -> new NodeTransition(from, NodeStatus.FAILED, true);

            // 节点内循环取消、用户取消挂起或拒绝审批：CANCELLED；应停图
            case LOOP_CANCELLED, USER_CANCELLED, USER_REJECTED -> new NodeTransition(from, NodeStatus.CANCELLED, true);
        };
    }
}
