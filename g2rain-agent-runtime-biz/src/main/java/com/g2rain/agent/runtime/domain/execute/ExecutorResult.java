package com.g2rain.agent.runtime.domain.execute;


import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 图执行器推进一次任务后的结果快照。
 *
 * <p>结果同时表达任务状态、当前焦点节点、用户消息和可恢复动作。上层可以据此决定继续
 * 执行、等待用户输入或结束响应，而不需要从多处状态字段重新推断执行器结论。例如，
 * {@code suspended("n2", "请补充订单号", FOLLOW_UP)} 表示任务在 {@code n2} 等待补参。</p>
 *
 * @param status         任务状态（运行中 / 挂起 / 成功 / 失败等）
 * @param currentNodeId  当前节点 id；可空
 * @param message        面向用户或上层的说明文案；可空
 * @param checkpointType 检查点类型（追问 / 审批）；非挂起时通常为 {@code null}
 * @param allowedActions 允许的恢复动作列表；挂起时通常取自检查点类型默认动作
 * @author alpha
 * @since 2026/8/21
 */
public record ExecutorResult(TaskStatus status, @Nullable String currentNodeId, @Nullable String message,
                             @Nullable CheckpointType checkpointType, List<ResumeAction> allowedActions) {

    /**
     * 创建没有焦点节点、消息或检查点的任务结果。
     *
     * @param status 任务状态
     */
    public ExecutorResult(TaskStatus status) {
        this(status, null, null, null, List.of());
    }

    /**
     * 创建带当前焦点节点、但不附带用户消息的任务结果。
     *
     * @param status        任务状态
     * @param currentNodeId 当前节点 id
     */
    public ExecutorResult(TaskStatus status, String currentNodeId) {
        this(status, currentNodeId, null, null, List.of());
    }

    /**
     * 创建带焦点节点和用户可见说明的任务结果。
     *
     * @param status        任务状态
     * @param currentNodeId 当前节点 id
     * @param message       说明文案；可空
     */
    public ExecutorResult(TaskStatus status, String currentNodeId, @Nullable String message) {
        this(status, currentNodeId, message, null, List.of());
    }

    /**
     * 创建带检查点类型的结果，并从检查点类型派生默认恢复动作。
     *
     * @param status         任务状态
     * @param currentNodeId  当前节点 id
     * @param message        说明文案；可空
     * @param checkpointType 检查点类型；可空则恢复动作为空列表
     */
    public ExecutorResult(TaskStatus status, String currentNodeId, @Nullable String message, @Nullable CheckpointType checkpointType) {
        var resumeActions = Optional.ofNullable(checkpointType).map(CheckpointType::defaultActions).orElseGet(List::of);
        this(status, currentNodeId, message, checkpointType, resumeActions);
    }

    /**
     * 创建挂起结果。
     *
     * <p>状态固定为 {@link TaskStatus#SUSPENDED}；未指定检查点类型时使用
     * {@link CheckpointType#FOLLOW_UP}，从而保证挂起结果始终带有可解释的恢复动作。</p>
     *
     * @param currentNodeId  挂起所在节点 id
     * @param message        追问/提示文案；可空
     * @param checkpointType 检查点类型；可空则按 FOLLOW_UP
     * @return 挂起结果
     */
    public static ExecutorResult suspended(String currentNodeId, @Nullable String message, @Nullable CheckpointType checkpointType) {
        CheckpointType type = Objects.requireNonNullElse(checkpointType, CheckpointType.FOLLOW_UP);
        return new ExecutorResult(TaskStatus.SUSPENDED, currentNodeId, message, type, type.defaultActions());
    }
}
