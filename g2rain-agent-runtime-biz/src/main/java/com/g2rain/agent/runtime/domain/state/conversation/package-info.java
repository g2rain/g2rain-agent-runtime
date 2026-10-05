/**
 * 会话侧领域模型：会话栅栏，以及任务前情、会话记忆、回合摘要所需的会话级定位信息。
 *
 * <p>
 * 当前类型为 {@link com.g2rain.agent.runtime.domain.state.conversation.ConversationFence}，
 * 对应会话 Hash 上的版本、活跃任务与未收尾 trace。任务前情 {@code Prior}、会话记忆
 * {@code EntityEntry}、回合摘要 {@code TaskRecord} 定义在 {@code domain.state.task}，
 * 由 {@link com.g2rain.agent.runtime.domain.state.TurnScope} 一并暴露。
 * </p>
 *
 * @author alpha
 * @since 2026/9/9
 */
@NullMarked
package com.g2rain.agent.runtime.domain.state.conversation;

import org.jspecify.annotations.NullMarked;
