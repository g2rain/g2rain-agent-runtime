package com.g2rain.agent.runtime.domain.entry;


import org.jspecify.annotations.Nullable;

/**
 * 描述入口拒绝响应所需的定位信息和展示文案。
 *
 * <p>该值对象不负责发送响应；调用方可用会话与任务标识关联日志，再把 {@code content}
 * 作为用户可见消息返回。任务尚未创建时 {@code taskId} 可以为空。</p>
 *
 * @param conversationId 会话 id
 * @param taskId         任务 id；可空
 * @param content        失败文案
 * @author alpha
 * @since 2026/10/3
 */
public record RejectedReply(String conversationId, @Nullable String taskId, String content) {

}
