package com.g2rain.agent.runtime.event;


/**
 * 运行事件发布器。
 *
 * <p>编排侧只调用本接口发事件，不关心底下是 SSE 还是其它接收端。
 * 实现负责扇出到已注册的 {@link EventSink}。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface EventPublisher {

    /**
     * 发布一条运行事件，并扇出到所有已注册的 Sink。
     *
     * @param event 待发布事件；调用方保证非 null
     */
    void publish(TurnEvent event);
}
