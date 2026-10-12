package com.g2rain.agent.runtime.engine.flow.finalize;


import org.jspecify.annotations.Nullable;

/**
 * 证据包里单个节点的事实。
 *
 * <p>节点状态为 DONE 且有 {@code target} 时，完成判定已保证目标写工具成功。</p>
 *
 * @param nodeId    节点 ID
 * @param title     节点标题；可空
 * @param objective 节点目标；可空
 * @param status    节点状态名
 * @param target    完成目标（写工具注册名）；可空
 * @param claimed   完成目标是否由首个写调用自动记录（可能只是前置写操作）
 * @param writable  节点所用技能是否有写工具
 * @param delegated 是否为委派出的前置节点
 * @param result    节点结论；未完成为空
 * @author alpha
 * @since 2026/8/21
 */
public record GoalNodeSummary(String nodeId, @Nullable String title, @Nullable String objective, String status,
                              @Nullable String target, boolean claimed, boolean writable, boolean delegated,
                              @Nullable String result) {

}
