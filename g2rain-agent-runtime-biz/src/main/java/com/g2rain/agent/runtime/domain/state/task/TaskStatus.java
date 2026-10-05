package com.g2rain.agent.runtime.domain.state.task;


import org.jspecify.annotations.Nullable;

/**
 * 任务的粗粒度生命周期状态。
 * <p>
 * 落在 {@link TaskMetadata#getStatus()}；挂起细节由 {@link TaskCheckpoint} 承载，本枚举只表达粗状态。
 * </p>
 *
 * @author alpha
 * @since 2026/9/9
 */
public enum TaskStatus {

    /**
     * 运行中。
     * <p>任务正在推进图调度或节点执行。</p>
     */
    RUNNING,

    /**
     * 挂起。
     * <p>等用户补充信息或审批；整任务暂停直至恢复动作到来，并通常伴随 TaskCheckpoint。</p>
     */
    SUSPENDED,

    /**
     * 成功结束。
     * <p>目标达成或图正常收口。</p>
     */
    SUCCEEDED,

    /**
     * 失败结束。
     * <p>执行出错且无法继续。</p>
     */
    FAILED,

    /**
     * 已取消。
     * <p>用户或系统主动取消本任务。</p>
     */
    CANCELED;

    /**
     * 是否为挂起粗状态（{@link #SUSPENDED}）；{@code null} 为 false。
     * <p>Task 侧是否「真挂起」还须同时有 Checkpoint。</p>
     *
     * @param status 粗状态；可空
     * @return 等于 SUSPENDED 时 true
     */
    public static boolean isSuspended(@Nullable TaskStatus status) {
        return status == SUSPENDED;
    }

    /**
     * 是否为过渡状态（{@link #RUNNING} 或 {@link #SUSPENDED}）；{@code null} 为 false。
     * <p>过渡状态表示 Task 尚未进入终态，仍可能继续执行或恢复。</p>
     *
     * @param status 粗状态；可空
     * @return 等于 RUNNING 或 SUSPENDED 时 true
     */
    public static boolean isTransient(@Nullable TaskStatus status) {
        return status == RUNNING || status == SUSPENDED;
    }

    /**
     * 判断任务是否已进入终态（成功、失败或取消）。
     *
     * @param status 粗状态；可空
     * @return SUCCEEDED / FAILED / CANCELED 为 true
     */
    public static boolean isTerminal(@Nullable TaskStatus status) {
        return status == SUCCEEDED || status == FAILED || status == CANCELED;
    }
}
