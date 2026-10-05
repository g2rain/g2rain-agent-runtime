package com.g2rain.agent.runtime.domain.graph;


import com.g2rain.agent.runtime.domain.plan.PlanStep;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务图上的一个可调度节点，对应计划中的一个步骤。
 * <p>
 * 持有节点身份、目标文案、技能键、当前状态、依赖列表，以及挂起/委派时的临时信息。
 * </p>
 * <p>节点同时保存规划得到的静态描述和执行期间产生的状态。静态字段决定如何执行，运行时字段用于恢复、审计和结果判断。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Getter
@Setter
@NoArgsConstructor
public class TaskNode {

    /**
     * 节点唯一 ID，图内寻址用。
     */
    private String nodeId;

    /**
     * 节点标题（展示用，可空）。例：{@code "查询订单"}。
     */
    private @Nullable String title;

    /**
     * 节点目标说明（给模型/执行器用，可空）。
     */
    private @Nullable String objective;

    /**
     * 绑定的技能键 skillKey；为空表示尚未绑定或纯编排节点。
     */
    private @Nullable String skillKey;

    /**
     * 当前生命周期状态；新建节点默认等待依赖检查。
     */
    private NodeStatus status = NodeStatus.PENDING;

    /**
     * 依赖的上游节点 ID 列表；全部 unlocksDownstream 后本节点才可 READY。
     */
    private List<String> dependsOn = new ArrayList<>();

    /**
     * 失败码；成功为空。
     */
    private @Nullable String errorCode;

    /**
     * 失败说明；成功为空。
     */
    private @Nullable String errorMessage;

    /**
     * 委派深度（发起节点为 0；前置节点 = 发起节点深度 + 1）。
     */
    private int delegateDepth;

    /**
     * 从计划步骤创建节点的运行时表示。
     *
     * <p>无依赖的节点初始为 {@link NodeStatus#READY}，有依赖的节点初始为
     * {@link NodeStatus#PENDING}。只复制步骤的静态描述，不复制 dependsOn
     * （由调用方另设或通过 {@code addNode} 建边）。</p>
     *
     * @param nodeId 分配给本节点的 ID
     * @param step   计划步骤，提供 title / objective / skillKey / 是否有依赖
     * @return 填好基础字段的新节点
     */
    public static TaskNode from(String nodeId, PlanStep step) {
        // 无依赖直接就绪，有依赖先挂 PENDING
        NodeStatus nodeStatus = NodeStatus.initial(
            step.dependsOn().isEmpty()
        );

        // 只抄计划上的身份、文案、技能与初始态，不在这里持久化
        TaskNode node = new TaskNode();
        node.setNodeId(nodeId);
        node.setTitle(step.title());
        node.setObjective(step.objective());
        node.setSkillKey(step.skillKey());
        node.setStatus(nodeStatus);
        return node;
    }
}
