package com.g2rain.agent.runtime.domain.state.task;


import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 任务等待用户或外部结果时使用的检查点类型。
 * <p>
 * 类型只描述用户需要完成的动作类别；可选动作和待处理内容分别由
 * {@link TaskCheckpoint#getAllowedActions()} 与 {@link TaskCheckpoint#getPendingAction()}
 * 保存，因此不需要再为每种等待原因建立独立枚举。
 * </p>
 *
 * @author alpha
 * @since 2026/9/9
 */
public enum CheckpointType {

    /**
     * 追问补充。
     * <p>缺参数或需要用户文字回复；默认可选 {@link ResumeAction#REPLY} / {@link ResumeAction#CANCEL}。</p>
     */
    FOLLOW_UP,

    /**
     * 审批确认。
     * <p>工具调用等需用户拍板；默认可选 {@link ResumeAction#CONFIRM} /
     * {@link ResumeAction#REJECT} / {@link ResumeAction#CANCEL}。</p>
     */
    APPROVAL,

    /**
     * 回查确认。
     * <p>写工具结果不确定、未配置 verify 时问用户上次操作是否已完成；
     * 默认可选 {@link ResumeAction#CONFIRM} / {@link ResumeAction#REJECT}。
     * CONFIRM 只改证据状态，不执行 pendingAction。</p>
     */
    VERIFY;

    /**
     * 返回该等待类型允许的默认恢复动作。
     *
     * @return APPROVAL → CONFIRM/REJECT/CANCEL；VERIFY → CONFIRM/REJECT；FOLLOW_UP → REPLY/CANCEL
     */
    public List<ResumeAction> defaultActions() {
        if (this == FOLLOW_UP) {
            return ResumeAction.followUps();
        }

        // 回查不得带 CANCEL，避免用户把「不确定是否写成功」直接收成放弃任务
        if (this == VERIFY) {
            return ResumeAction.verifies();
        }

        return ResumeAction.approvals();
    }

    /**
     * 返回用户未显式指定动作时可安全采用的默认动作。
     * <p>APPROVAL / VERIFY 不得默认 CONFIRM；FOLLOW_UP 无动作时按 REPLY 续跑补参。</p>
     *
     * @return FOLLOW_UP → REPLY；APPROVAL / VERIFY → {@code null}
     */
    public @Nullable ResumeAction defaultResumeAction() {
        if (this == FOLLOW_UP) {
            return ResumeAction.REPLY;
        }

        // 审批和回查没有安全默认确认，必须等用户显式选择
        return null;
    }
}

