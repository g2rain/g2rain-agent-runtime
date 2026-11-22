package com.g2rain.agent.runtime.domain.state.task;


import org.jspecify.annotations.Nullable;

/**
 * 本轮恢复审计：记下被消费断点所属任务与恢复动作，persist 时按任务写入恢复记录。
 *
 * @param taskId    被恢复的任务 id
 * @param action    用户恢复动作
 * @param messageId 触发恢复的消息 id；可空
 * @param actedAt   恢复动作发生时间（epoch ms）
 * @author alpha
 * @since 2026/11/22
 */
public record ResumeMark(String taskId, ResumeAction action, @Nullable String messageId, long actedAt) {

}
