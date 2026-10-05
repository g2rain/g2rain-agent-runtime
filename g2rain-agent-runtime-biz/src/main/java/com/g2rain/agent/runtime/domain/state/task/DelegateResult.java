package com.g2rain.agent.runtime.domain.state.task;


import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import org.jspecify.annotations.Nullable;

/**
 * 委派收口观察：前置节点未成功时写入发起节点 {@code state.delegateResult}，投影消费后清除。
 *
 * <p>子节点 FAILED / CANCELLED / SKIPPED 不连锁失败父节点；本对象只把终态与错误交给模型观察。</p>
 *
 * @param skillKey     前置节点技能键；可空
 * @param status       前置节点终态
 * @param errorCode    错误码；可空
 * @param errorMessage 错误说明；可空
 * @author alpha
 * @since 2026/10/4
 */
public record DelegateResult(@Nullable String skillKey, NodeStatus status, @Nullable String errorCode,
                             @Nullable String errorMessage) {
}
