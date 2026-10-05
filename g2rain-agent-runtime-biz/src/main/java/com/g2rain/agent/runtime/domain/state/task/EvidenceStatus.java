package com.g2rain.agent.runtime.domain.state.task;


/**
 * 工具证据的生命周期状态。
 *
 * <p>状态区分“已发起但未完成”“结果已知”和“是否发起本身不确定”，使恢复逻辑能够在未知
 * 时先核查而不是盲目重放。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public enum EvidenceStatus {

    /**
     * 调用已登记且 MySQL 已确认，但工具尚未返回结果。
     */
    STARTED,

    /**
     * 调用成功。
     * <p>工具已返回；是否业务成功、结果是否为空再看 {@code businessOk} / {@code empty}。</p>
     */
    SUCCESS,

    /**
     * 调用失败或被拦截器拒绝。
     * <p>结果已知为失败，可以按失败分类展示，不必先核查是否已执行。</p>
     */
    FAILED,

    /**
     * 非写工具超时。
     * <p>读类调用在时限内没有结果；一般可安全视为未完成，不必按写工具 UNKNOWN 处理。</p>
     */
    TIMEOUT,

    /**
     * 工具未调用或已明确取消，包括预登记失败以及查清后的 UNKNOWN_PRE_CALL。
     */
    CANCELLED,

    /**
     * 写工具可能已经调用，但结果不确定；恢复时必须先核查，不得直接重放。
     */
    UNKNOWN,

    /**
     * 调用前登记的持久化结果不确定；在核查并收口前严禁调用工具。
     */
    UNKNOWN_PRE_CALL
}
