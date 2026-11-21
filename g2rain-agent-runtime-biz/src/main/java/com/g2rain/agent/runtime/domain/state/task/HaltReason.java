package com.g2rain.agent.runtime.domain.state.task;

/**
 * 节点停下等用户的原因，由运行时判定、随检查点保存。
 *
 * <p>原因决定检查点类型（恢复协议）、追问是否带待补参数、用户回复如何认定。
 * 例：删除被前置条件拒绝后追问如何处理 → {@link #DENIED}，检查点为 {@link CheckpointType#FOLLOW_UP}，不带待补参数。</p>
 *
 * @author alpha
 * @since 2026/11/21
 */
public enum HaltReason {

    /**
     * 缺少只能由用户提供的参数；待补参数必填，用户回复按用户来源认定这些参数。
     */
    MISSING_INPUT(CheckpointType.FOLLOW_UP),

    /**
     * 查询返回多条候选，请用户选择；待补参数可选，选中的值仍按查询结果核对来源。
     */
    AMBIGUOUS(CheckpointType.FOLLOW_UP),

    /**
     * 查询为空或业务失败，需要用户澄清；不带待补参数。
     */
    UNRESOLVED(CheckpointType.FOLLOW_UP),

    /**
     * 调用被前置条件拒绝，由用户决定如何处理；不带待补参数。
     */
    DENIED(CheckpointType.FOLLOW_UP),

    /**
     * 写操作须经用户确认。
     */
    APPROVAL(CheckpointType.APPROVAL),

    /**
     * 写操作结果不确定，请用户核实是否已生效。
     */
    VERIFY(CheckpointType.VERIFY);

    /**
     * 对应的检查点类型。
     */
    private final CheckpointType checkpointType;

    HaltReason(CheckpointType checkpointType) {
        this.checkpointType = checkpointType;
    }

    /**
     * @return 对应的检查点类型
     */
    public CheckpointType checkpointType() {
        return checkpointType;
    }
}
