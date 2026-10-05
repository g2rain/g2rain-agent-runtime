package com.g2rain.agent.runtime.engine.flow.graph;


import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import org.jspecify.annotations.Nullable;

/**
 * GraphResumeActions：任务级 {@link ResumeAction} 在图推进侧的语义判定。
 * <p>
 * 集中回答「是否关挂起 / 是否唤醒焦点」等布尔问题。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
final class GraphResumeActions {

    /**
     * 禁止实例化：只提供静态动作判定。
     */
    private GraphResumeActions() {

    }

    /**
     * 该续跑动作是否表示拒绝或取消，从而应关闭挂起而非进入节点。
     *
     * @param action 用户续跑动作；可空
     * @return 为 REJECT 或 CANCEL 则为 true
     */
    static boolean closesSuspend(@Nullable ResumeAction action) {
        return action == ResumeAction.REJECT || action == ResumeAction.CANCEL;
    }

    /**
     * 该续跑动作是否应唤醒当前挂起焦点（确认审批或文字回复）。
     *
     * @param action 用户续跑动作；可空
     * @return 为 CONFIRM 或 REPLY 则为 true
     */
    static boolean wakesFocus(@Nullable ResumeAction action) {
        return action == ResumeAction.CONFIRM || action == ResumeAction.REPLY;
    }

    /**
     * 是否为取消类续跑动作。
     *
     * @param action 用户续跑动作；可空
     * @return 为 CANCEL 则为 true
     */
    static boolean isCancel(@Nullable ResumeAction action) {
        return action == ResumeAction.CANCEL;
    }

    /**
     * 是否为确认类续跑动作（如审批通过）。
     *
     * @param action 用户续跑动作；可空
     * @return 为 CONFIRM 则为 true
     */
    static boolean isConfirm(@Nullable ResumeAction action) {
        return action == ResumeAction.CONFIRM;
    }
}
