package com.g2rain.agent.runtime.domain.state.conversation;


import com.fasterxml.jackson.annotation.JsonIgnore;
import org.jspecify.annotations.Nullable;

/**
 * 会话栅栏：会话 Hash 上的版本、活跃任务与未收尾 trace。
 *
 * <p>对应 Redis {@code state} Hash（{@code userId} / {@code activeTaskId} / {@code version} /
 * {@code lastTraceId} / {@code updatedAt}）。{@code conversationId} 在 key 上，不进 Hash。
 * {@code lastTraceId} 非空表示上一 Turn（本轮）尚未收尾，begin 须先续跑或失败收口。</p>
 *
 * @param conversationId 会话 id
 * @param userId         所属用户；Hash 尚未写入时可空
 * @param activeTaskId   当前任务；无任务为空
 * @param version        乐观版本
 * @param lastTraceId    上一轮未收尾则为非空
 * @param updatedAt      毫秒时间戳
 * @author alpha
 * @since 2026/10/3
 */
public record ConversationFence(@JsonIgnore String conversationId, @Nullable String userId,
                                @Nullable String activeTaskId, long version, @Nullable String lastTraceId,
                                long updatedAt) {

}
