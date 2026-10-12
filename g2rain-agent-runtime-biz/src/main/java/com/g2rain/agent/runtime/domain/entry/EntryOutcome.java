package com.g2rain.agent.runtime.domain.entry;

/**
 * 入口结论：本轮请求在入口被如何分流。
 *
 * <p>与 {@link EntryResult} 的四个工厂方法一一对应。</p>
 *
 * @author alpha
 * @since 2026/10/11
 */
public enum EntryOutcome {

    /**
     * 受理：交给本轮决策判断如何推进。
     */
    ACCEPT,

    /**
     * 续跑：按恢复动作在挂起任务上继续。
     */
    CONTINUE,

    /**
     * 换人：请求的 Agent 与挂起任务绑定的不一致，换 Agent 重开。
     */
    SWITCH,

    /**
     * 拒绝：直接回复拒绝文案，不进入后续阶段。
     */
    REJECT
}
