package com.g2rain.agent.runtime.domain.graph;


/**
 * 任务图节点的生命周期状态。
 * <p>
 * 调度器靠这些状态判断：能不能入队、会不会放开下游、本轮循环该产出什么事件。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public enum NodeStatus {

    /**
     * 待依赖。
     * <p>还有上游没完成，暂不可调度；上游齐了后会被升为 READY。</p>
     */
    PENDING,

    /**
     * 就绪。
     * <p>依赖已满足（或本来就没有依赖），可以被本波选中执行。</p>
     */
    READY,

    /**
     * 运行中。
     * <p>节点正在被执行器跑（例如 ReAct 循环里）。</p>
     */
    RUNNING,

    /**
     * 等待委派子节点。
     * <p>本节点已把活交给子节点，自己先停着；子节点终态后由 resumeAwaiting 回醒。</p>
     */
    WAITING_DELEGATE,

    /**
     * 挂起。
     * <p>需要用户补充信息或审批，等 ResumeAction 后再继续。</p>
     */
    SUSPENDED,

    /**
     * 完成。
     * <p>成功跑完；会放开依赖它的下游节点。</p>
     */
    DONE,

    /**
     * 失败。
     * <p>执行出错或委派子节点异常结束；属终态，不放开下游。</p>
     */
    FAILED,

    /**
     * 跳过。
     * <p>本节点被显式跳过；与 DONE 一样会放开下游。</p>
     */
    SKIPPED,

    /**
     * 取消。
     * <p>任务或节点被取消；属终态。</p>
     */
    CANCELLED;

    /**
     * 判断节点是否仍在等待上游依赖。
     *
     * @return 当前为 {@link #PENDING} 时为 true
     */
    public boolean isPending() {
        return this == PENDING;
    }

    /**
     * 判断节点是否正在等待用户恢复。
     *
     * @return 当前为 {@link #SUSPENDED} 时为 true
     */
    public boolean isSuspended() {
        return this == SUSPENDED;
    }

    /**
     * 判断节点当前是否不再挂起。
     *
     * @return 不是 {@link #SUSPENDED} 时为 true，含运行中和终态
     */
    public boolean isUnsuspended() {
        return this != SUSPENDED;
    }

    /**
     * 是否可被调度入波。
     * <p>仅 PENDING / READY；其它状态不会进 nextWave。</p>
     *
     * @return 可入波时为 {@code true}
     */
    public boolean isSchedulable() {
        return this == PENDING || this == READY;
    }

    /**
     * {@link #isSchedulable()} 的取反，便于过滤。
     *
     * @return 不可入波时为 {@code true}
     */
    public boolean isNotSchedulable() {
        return !isSchedulable();
    }

    /**
     * 判断节点是否处于非运行状态。
     *
     * @return 当前状态不是 {@link #RUNNING} 时返回 {@code true}
     */
    public boolean isNonRunning() {
        return this != RUNNING;
    }

    /**
     * 是否放开下游依赖。
     * <p>DONE / SKIPPED 视为「上游已过」；FAILED 等不放开。</p>
     *
     * @return 下游可以入波时为 {@code true}
     */
    public boolean unlocksDownstream() {
        return this == DONE || this == SKIPPED;
    }

    /**
     * 是否仍处在「还没跑完、也没挂起/委派」的活跃区间。
     * <p>PENDING / READY / RUNNING。</p>
     *
     * @return 仍在活跃区间时为 {@code true}
     */
    public boolean isActive() {
        return this == PENDING || this == READY || this == RUNNING;
    }

    /**
     * 是否已到终态，不会再被调度。
     * <p>DONE / FAILED / CANCELLED / SKIPPED；不含 SUSPENDED、WAITING_DELEGATE。</p>
     *
     * @return 已终态时为 {@code true}
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == CANCELLED || this == SKIPPED;
    }

    /**
     * 把当前节点状态映射成节点循环事件（NodeEvent）。
     * <p>
     * DONE → LOOP_DONE；WAITING_DELEGATE / SUSPENDED / CANCELLED 各有对应事件；
     * 其余（含 FAILED）一律映射为 LOOP_FAILED。
     * </p>
     *
     * @return 对应的 NodeEvent
     */
    public NodeEvent loopEvent() {
        // 按节点态映射成循环事件
        return switch (this) {
            // 成功完成
            case DONE -> NodeEvent.LOOP_DONE;
            // 等委派子节点
            case WAITING_DELEGATE -> NodeEvent.LOOP_WAITING_DELEGATE;
            // 挂起等人
            case SUSPENDED -> NodeEvent.LOOP_SUSPENDED;
            // 已取消
            case CANCELLED -> NodeEvent.LOOP_CANCELLED;
            // FAILED 及其余未单列的态 → 失败事件
            default -> NodeEvent.LOOP_FAILED;
        };
    }

    /**
     * 按是否无依赖给出创建节点状态。
     *
     * @param noDependencies {@code true} 无依赖 → READY；{@code false} 有依赖 → PENDING
     * @return 初始状态
     */
    public static NodeStatus initial(boolean noDependencies) {
        return noDependencies ? READY : PENDING;
    }
}
