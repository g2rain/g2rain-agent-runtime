package com.g2rain.agent.runtime.controller;


import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.service.ConversationService;
import com.g2rain.agent.runtime.service.impl.SseEventSink;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ConversationController（会话 HTTP 入口）：对外暴露智能体对话接口。
 * <p>
 * Turn（本轮）= 一次用户请求从进入到产出结果的完整处理。
 * 本控制器提供 SSE（Server-Sent Events）流式通道：客户端 POST 本轮请求后保持长连接，
 * 过程中接收进度/节点/工具等事件帧，结束时收到业务收口与 DONE。
 * </p>
 * <p>
 * 例：{@code POST /v1/chat/completions}，Body 为 {@link TurnRequest}，
 * Response 为 {@code text/event-stream}，由 {@link SseEventSink} 推送 {@code TurnEvent}。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "智能体会话", description = "Runtime Chat 入口")
public class ConversationController {

    /**
     * SSE 事件接收端。
     * <p>负责注册长连接、在虚拟线程里跑本轮回调，并把事件帧推给客户端。</p>
     */
    private final SseEventSink sseEventSink;

    /**
     * 会话服务。
     * <p>实际执行本轮编排（建会话骨架、加锁、跑 Turn 流水线）。</p>
     */
    private final ConversationService conversationService;

    /**
     * SSE 流式发送本轮对话请求。
     * <p>
     * 校验通过的 {@link TurnRequest} 交给 {@link SseEventSink#register}：
     * 打开 emitter → 发 STARTED → 调用 {@link ConversationService#send} 跑本轮 →
     * 发业务收口帧与 DONE → 关闭流。
     * </p>
     *
     * @param request 本轮请求（用户输入、会话 id、恢复动作等）；经 {@code @Validated} 校验
     * @return 挂起的 {@link SseEmitter}，供 HTTP 层持续写出事件流
     */
    @Operation(summary = "SSE 流式发送事件消息")
    @PostMapping(path = "/v1/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendStream(@RequestBody @Validated TurnRequest request) {
        // 注册 SSE：长连接内用 conversationService.send 作为本轮业务回调
        return sseEventSink.register(request, conversationService::send);
    }
}
