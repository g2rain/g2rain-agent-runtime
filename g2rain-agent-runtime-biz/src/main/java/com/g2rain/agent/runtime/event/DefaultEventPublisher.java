package com.g2rain.agent.runtime.event;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认事件发布器。
 *
 * <p>把同一条 {@link TurnEvent} 按容器注入顺序交给全部 {@link EventSink}。
 * 本类不缓冲、不改写事件；某个 Sink 抛错会中断后续扇出。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Component
@RequiredArgsConstructor
public final class DefaultEventPublisher implements EventPublisher {

    /**
     * 容器注入的全部接收端；发布期间只读，按列表顺序逐个 {@code accept}。
     */
    private final List<EventSink> sinks;

    /**
     * 将事件扇出到每个 Sink。
     *
     * @param event 待发布的运行事件
     */
    @Override
    public void publish(TurnEvent event) {
        // 同一对象交给每个接收端；接收端只做自己的投递，互不改信封
        sinks.forEach(sink -> sink.accept(event));
    }
}
