package com.g2rain.agent.runtime.domain.state;


import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * 本轮结果状态，随结果帧下发给客户端。
 *
 * <p>这是展示模型，不是任务持久化状态；{@link #of(TaskStatus)} 把内部任务生命周期
 * 映射为客户端可理解的四种结局。</p>
 *
 * @author alpha
 * @since 2026/8/28
 */
public enum TurnStatus {

    /**
     * 等待中：等用户补充、审批或核实。
     */
    WAITING,

    /**
     * 完成：任务成功收口，或本轮只回复未开任务。
     */
    DONE,

    /**
     * 失败：执行出错，或任务仍为运行中却被拿来做本轮收口映射。
     */
    FAILED,

    /**
     * 取消：用户或系统主动取消任务。
     */
    CANCELLED;

    /**
     * 从任务粗状态映射为客户端状态。
     *
     * <p>任务仍为 {@link TaskStatus#RUNNING} 时，本轮已经进入收口路径却没有终态，因此按
     * {@link #FAILED} 暴露，避免客户端把未完成任务误显示为成功。</p>
     *
     * @param status 任务状态
     * @return 本轮态
     */
    public static TurnStatus of(@Nullable TaskStatus status) {
        // 任务状态缺失按失败暴露，防止收口事件把空状态显示成成功
        if (Objects.isNull(status)) {
            return FAILED;
        }

        return switch (status) {
            case RUNNING, FAILED -> FAILED;
            case SUCCEEDED -> DONE;
            case SUSPENDED -> WAITING;
            case CANCELED -> CANCELLED;
        };
    }
}
