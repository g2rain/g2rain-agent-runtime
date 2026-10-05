package com.g2rain.agent.runtime.engine.flow.finalize;

import org.jspecify.annotations.Nullable;

/**
 * 证据包里单个节点的摘要行。
 *
 * @param nodeId        节点 ID
 * @param skillKey      技能键；可空
 * @param status        节点状态名；可空
 * @param resultSummary 首条成功证据文案；可空
 * @param finalMessage  节点产出正文；可空
 * @author alpha
 * @since 2026/8/21
 */
public record GoalNodeSummary(String nodeId, @Nullable String skillKey, @Nullable String status,
                              @Nullable String resultSummary, @Nullable String finalMessage) {

}
