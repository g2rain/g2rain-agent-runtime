package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;

/**
 * 写工具预登记结果：只有 {@link Proceed} 才允许调用写工具。
 *
 * @author alpha
 * @since 2026/10/3
 */
public sealed interface PrewriteResult {

    /**
     * 预写成功，调用方必须用该 evidenceId 调写工具，返回后 settle。
     *
     * @param evidenceId 本次预写的证据 id
     */
    record Proceed(String evidenceId) implements PrewriteResult {

    }

    /**
     * 已有 SUCCESS 证据，复用、不再调用工具。
     *
     * @param evidenceId 可复用的证据 id
     */
    record Reused(String evidenceId) implements PrewriteResult {

    }

    /**
     * 不得调用：同动作未收口（STARTED / UNKNOWN / UNKNOWN_PRE_CALL）。
     *
     * @param evidenceId 相关证据 id
     * @param status     阻挡时的证据状态
     */
    record Blocked(String evidenceId, EvidenceStatus status) implements PrewriteResult {

    }
}
