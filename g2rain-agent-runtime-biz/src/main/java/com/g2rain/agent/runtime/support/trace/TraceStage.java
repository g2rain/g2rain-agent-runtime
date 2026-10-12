package com.g2rain.agent.runtime.support.trace;

/**
 * 链路环节：每个环节对应一组轨迹事件，可单独开关。
 *
 * <p>顺序与一轮对话的推进顺序一致。</p>
 *
 * @author alpha
 * @since 2026/10/11
 */
public enum TraceStage {

    /**
     * 入口分流：受理、续跑、换人或拒绝。
     */
    ENTRY,

    /**
     * 本轮决策：推进模式与执行 Agent。
     */
    DECISION,

    /**
     * 任务生命周期：新建、放弃、缺失。
     */
    TASK,

    /**
     * 技能装载。
     */
    HYDRATE,

    /**
     * 规划：跳过、出图、失败。
     */
    PLAN,

    /**
     * 任务图推进：下一步执行哪些节点。
     */
    GRAPH,

    /**
     * 节点执行：开始、结束、委派结果。
     */
    NODE,

    /**
     * 节点内循环：每轮、修复拦截、退出。
     */
    REACT,

    /**
     * 工具调用。
     */
    TOOL,

    /**
     * 大模型调用。
     */
    MODEL,

    /**
     * 本轮收口。
     */
    FINALIZE,

    /**
     * 状态落盘体量。
     */
    STATE
}
