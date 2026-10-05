package com.g2rain.agent.runtime.domain.graph;


/**
 * 驱动节点生命周期迁移的事件。
 *
 * <p>事件表示“发生了什么”，不直接保存迁移后的状态；节点状态机根据事件和当前状态决定
 * 是否允许迁移，以及是否需要通知下游节点或用户。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public enum NodeEvent {

    /**
     * 调度选中。
     * <p>本波选中该节点，准备进入节点内循环。</p>
     */
    SELECTED,

    /**
     * 循环完成。
     * <p>节点内循环正常完成，对应 {@link NodeStatus#DONE}。</p>
     */
    LOOP_DONE,

    /**
     * 循环挂起。
     * <p>需要追问或审批，节点进入 {@link NodeStatus#SUSPENDED}。</p>
     */
    LOOP_SUSPENDED,

    /**
     * 循环等委派。
     * <p>已把工作交给子节点，父节点进入 {@link NodeStatus#WAITING_DELEGATE}。</p>
     */
    LOOP_WAITING_DELEGATE,

    /**
     * 循环失败。
     * <p>节点内循环失败，或未单列的终态被映射为失败事件。</p>
     */
    LOOP_FAILED,

    /**
     * 循环取消。
     * <p>节点内循环取消，对应 {@link NodeStatus#CANCELLED}。</p>
     */
    LOOP_CANCELLED,

    /**
     * 用户拒绝。
     * <p>审批不通过，关闭当前挂起。</p>
     */
    USER_REJECTED,

    /**
     * 用户取消。
     * <p>用户取消挂起恢复，不再推进焦点。</p>
     */
    USER_CANCELLED
}
