/**
 * 本轮运行事件的模型、发布与投递。
 *
 * <p>只有两种帧：过程提示 PROGRESS 与本轮结果 RESULT，共用 {@link com.g2rain.agent.runtime.event.TurnEvent}，
 * 经 {@link com.g2rain.agent.runtime.event.EventPublisher} 扇出到全部
 * {@link com.g2rain.agent.runtime.event.EventSink}。本包不持有会话或任务状态，
 * 也不决定何时发事件；只提供事件结构和投递通道。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.event;

import org.jspecify.annotations.NullMarked;
