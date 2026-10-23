package com.g2rain.agent.runtime.domain.state.progress;

/**
 * 执行过程环节。
 */
public enum StepStage {

    /**
     * 意图判断与选员工。
     */
    ROUTE,

    /**
     * 规划节点。
     */
    PLAN,

    /**
     * 执行一个节点。
     */
    NODE,

    /**
     * ReAct 的一轮。
     */
    ROUND,

    /**
     * 一次模型调用。
     */
    MODEL,

    /**
     * 一次工具调用。
     */
    TOOL,

    /**
     * 目标校验。
     */
    VERIFY
}
