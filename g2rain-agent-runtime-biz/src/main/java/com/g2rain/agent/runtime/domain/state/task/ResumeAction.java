package com.g2rain.agent.runtime.domain.state.task;


import java.util.List;

/**
 * 用户恢复检查点时允许选择的动作。
 * <p>
 * 具体某次挂起允许哪些动作，由 {@link TaskCheckpoint#getAllowedActions()} 声明。
 * 取值：{@code REPLY} / {@code CONFIRM} / {@code REJECT} / {@code CANCEL}。
 * </p>
 *
 * @author alpha
 * @since 2026/9/9
 */
public enum ResumeAction {

    /**
     * 文字回复。
     * <p>用户补充说明或补参；用于 {@link CheckpointType#FOLLOW_UP}。</p>
     */
    REPLY,

    /**
     * 确认待批准动作；仅用于审批检查点，核实检查点中的确认只更新事实状态。
     */
    CONFIRM,

    /**
     * 拒绝。
     * <p>拍板不通过；通常收口挂起、不再推进焦点。</p>
     */
    REJECT,

    /**
     * 取消。
     * <p>用户放弃当前挂起事项。</p>
     */
    CANCEL;

    /**
     * 审批挂起场景默认可选动作：确认、拒绝、取消。
     *
     * @return 不可变列表
     */
    public static List<ResumeAction> approvals() {
        return List.of(CONFIRM, REJECT, CANCEL);
    }

    /**
     * 追问挂起场景默认可选动作：文字回复、取消。
     *
     * @return 不可变列表
     */
    public static List<ResumeAction> followUps() {
        return List.of(REPLY, CANCEL);
    }

    /**
     * 回查挂起场景默认可选动作：确认、拒绝（不执行 pendingAction）。
     *
     * @return 不可变列表
     */
    public static List<ResumeAction> verifies() {
        return List.of(CONFIRM, REJECT);
    }
}
