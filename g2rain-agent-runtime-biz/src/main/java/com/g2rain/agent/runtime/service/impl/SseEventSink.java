package com.g2rain.agent.runtime.service.impl;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.engine.TurnResult;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.agent.runtime.event.EventSink;
import com.g2rain.agent.runtime.event.TurnEvent;
import com.g2rain.agent.runtime.event.TurnEventType;
import com.g2rain.agent.runtime.event.TurnEvents;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.common.concurrent.ContextExecutors;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 基于 Spring {@link SseEmitter} 的运行事件接收端。
 *
 * <p>以 traceId 维护本进程内的流连接，写出前递增序号写入 SSE {@code id:}，
 * 并把 {@link TurnEventType} 写入 {@code event:}。发出 {@link TurnEventType#RESULT} 后不再推帧。
 * 每条连接自带一个心跳虚拟线程：空闲满一个间隔就写一行 SSE 注释 {@code : ping}；
 * 连接收口后，心跳线程在下次醒来时退出。心跳不是事件，不占序号。
 * 本类不编排本轮业务，只投递事件。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseEventSink implements EventSink {

    /**
     * 心跳间隔未配置或非法时的默认值（毫秒）。
     */
    private static final long DEFAULT_PING_MILLIS = 15_000L;

    /**
     * 进行中的 SSE 会话表，键为 traceId；只保存当前进程内仍可发送的连接。
     */
    private final ConcurrentHashMap<String, StreamSession> sessions = new ConcurrentHashMap<>();

    /**
     * 提供流超时和心跳间隔配置。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 注册一条 SSE 流：绑定 emitter，在虚拟线程中执行 callback。
     *
     * <p>首条过程提示由会话服务在锁内发布。编排异常时若尚未发出结果帧，
     * 则补一条失败结果，再 complete。</p>
     *
     * @param request  本轮请求
     * @param callback 实际跑本轮编排的函数
     * @return 已绑定生命周期回调的 {@link SseEmitter}
     */
    public SseEmitter register(TurnRequest request, Function<TurnRequest, TurnResult> callback) {
        // traceId 同时承担连接索引和事件路由键，必须在异步任务启动前固定下来。
        String streamId = RuntimeUtils.requireTraceId();
        Duration timeout = runtimeProperties.getStream().getTimeout();
        long millis = timeout.isZero() || timeout.isNegative() ? 0L : timeout.toMillis();
        SseEmitter emitter = new SseEmitter(millis);
        bind(streamId, emitter);
        // 回调在虚拟线程中执行，SSE 线程只负责连接生命周期和事件发送。
        emitter.onCompletion(() -> unbind(streamId));
        emitter.onError(_ -> unbind(streamId));
        ContextExecutors.startVirtual(() -> {
            String traceId = RuntimeUtils.requireTraceId();
            try {
                callback.apply(request);
                complete(traceId);
            } catch (RuntimeException ex) {
                log.error("[SSE] turn failed traceId={}", traceId, ex);
                StreamSession session = sessions.get(traceId);
                if (Objects.isNull(session) || !session.ended.get()) {
                    // 尚未发出结果帧才补一条失败结果，保证客户端收到收口
                    String conversationId = request.getConversationId();
                    String text = RuntimeMessage.ERROR_GENERIC.text();
                    accept(TurnEvents.failed(traceId, conversationId, text));
                }
                complete(traceId);
            } finally {
                unbind(traceId);
            }
        });
        return emitter;
    }

    /**
     * 把事件推到对应 traceId 的 SSE 连接；无会话则静默丢弃。
     *
     * @param event 待推送事件
     */
    @Override
    public void accept(@NonNull TurnEvent event) {
        // 连接关闭或已发出结果帧后丢弃迟到事件，避免继续触碰已经完成的 emitter。
        String streamId = event.traceId();
        StreamSession session = sessions.get(streamId);
        if (Objects.isNull(session) || session.ended.get()) {
            return;
        }

        try {
            // 序号在发送前递增，只给数据帧写 id；心跳注释不占号。
            long sequence = session.sequence.incrementAndGet();
            session.lastEmit.set(System.currentTimeMillis());
            var data = SseEmitter
                .event()
                .id(String.valueOf(sequence))
                .name(event.type().name())
                .data(event);

            session.emitter.send(data);
            // 结果帧是业务流的终止标记：之后不再发送数据帧，心跳也随之停止。
            if (event.type() == TurnEventType.RESULT) {
                session.close();
            }
        } catch (IOException | IllegalStateException ex) {
            drop(streamId, session, ex);
        }
    }

    /**
     * 建立流标识到 emitter 的绑定。
     *
     * <p>空标识不建立连接，避免把无法路由的流放入全局会话表。绑定后立即启动本流的心跳线程。</p>
     *
     * @param streamId 流标识，通常为本轮 traceId
     * @param emitter  Spring SSE 发射器
     */
    private void bind(String streamId, SseEmitter emitter) {
        // 空标识无法路由，不进会话表
        if (Strings.isBlank(streamId)) {
            return;
        }

        StreamSession session = new StreamSession(emitter);
        sessions.put(streamId, session);

        ContextExecutors.startVirtual(() ->
            keepAlive(streamId, session)
        );
    }

    /**
     * 本流心跳：距上次写出满一个间隔就写一行 {@code : ping}，直到本流收口。
     *
     * <p>有数据写出会顺延下一次心跳；收口后在下次醒来时退出，不再写出。
     * 写失败按断连处理，收口后的写失败直接忽略。</p>
     *
     * @param streamId 流标识
     * @param session  所属会话
     */
    @SuppressWarnings("BusyWait")
    private void keepAlive(String streamId, StreamSession session) {
        long interval = pingInterval();
        try {
            while (!session.ended.get()) {
                long start = System.currentTimeMillis();
                long wait = interval - (start - session.lastEmit.get());
                if (wait > 0) {
                    Thread.sleep(wait);
                    continue;
                }

                session.lastEmit.set(start);
                var data = SseEmitter
                    .event()
                    .comment("ping");

                session.emitter.send(data);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException ex) {
            if (!session.ended.get()) {
                drop(streamId, session, ex);
            }
        }
    }

    /**
     * 心跳间隔；零或负值按默认 15 秒。
     *
     * @return 间隔毫秒
     */
    private long pingInterval() {
        Duration interval = runtimeProperties.getStream().getPingInterval();
        return interval.isZero() || interval.isNegative() ? DEFAULT_PING_MILLIS : interval.toMillis();
    }

    /**
     * 写出失败：解绑并以错误结束连接。
     *
     * @param streamId 流标识
     * @param session  失败的会话
     * @param ex       写出异常
     */
    private void drop(String streamId, StreamSession session, Exception ex) {
        log.error("[SSE] emit failed traceId={}", streamId, ex);
        sessions.remove(streamId, session);
        session.close();
        try {
            session.emitter.completeWithError(ex);
        } catch (Exception ce) {
            log.error("[SSE] failed traceId={}", streamId, ce);
        }
    }

    /**
     * 移除流绑定；重复调用是安全的。
     *
     * @param streamId 流标识
     */
    private void unbind(String streamId) {
        // 空标识无绑定可清
        if (Strings.isBlank(streamId)) {
            return;
        }
        StreamSession session = sessions.remove(streamId);
        if (Objects.nonNull(session)) {
            session.close();
        }
    }

    /**
     * 主动完成一条 SSE 流并清理绑定。
     *
     * @param streamId 流标识
     */
    private void complete(String streamId) {
        StreamSession session = sessions.remove(streamId);
        // 连接已解绑则不再 complete，避免对已关闭 emitter 二次收口
        if (Objects.isNull(session)) {
            return;
        }
        session.close();
        try {
            session.emitter.complete();
        } catch (Exception ex) {
            log.error("[SSE] failed traceId={}", streamId, ex);
        }
    }

    /**
     * 单条 SSE 流会话。
     */
    private static final class StreamSession {

        /**
         * Spring SSE 发射器。
         */
        private final SseEmitter emitter;

        /**
         * 本连接已发送数据帧序号，从 1 起；写入 SSE {@code id:}。
         */
        private final AtomicLong sequence = new AtomicLong(0);

        /**
         * 最近一次写出的毫秒时间，心跳据此判断是否空闲。
         */
        private final AtomicLong lastEmit = new AtomicLong(System.currentTimeMillis());

        /**
         * 已收口：发出结果帧或连接已结束，之后不再推帧。
         */
        private final AtomicBoolean ended = new AtomicBoolean(false);

        private StreamSession(SseEmitter emitter) {
            this.emitter = emitter;
        }

        /**
         * 收口：标记结束，之后不再推帧，心跳线程下次醒来即退出；重复调用是安全的。
         */
        private void close() {
            ended.set(true);
        }
    }
}
