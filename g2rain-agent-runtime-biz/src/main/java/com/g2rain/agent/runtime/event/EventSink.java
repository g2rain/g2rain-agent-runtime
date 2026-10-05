package com.g2rain.agent.runtime.event;


/**
 * 运行事件接收端。
 *
 * <p>例如 {@link com.g2rain.agent.runtime.service.impl.SseEventSink} 按 trace 推到 SSE 连接。
 * 同一条事件可由多个 Sink 消费；实现不得假设自己是唯一接收方。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface EventSink {

    /**
     * 接收并处理一条运行事件。
     *
     * @param event 待投递事件；调用方保证非 null
     */
    void accept(TurnEvent event);
}
