package com.g2rain.agent.runtime.domain.state.progress;

/**
 * 步骤状态。
 */
public enum StepStatus {

    /**
     * 进行中。
     */
    RUNNING,

    /**
     * 已完成。
     */
    DONE,

    /**
     * 已完成，但附带须让用户知道的警告。
     */
    WARN,

    /**
     * 失败。
     */
    FAILED
}
