package com.g2rain.agent.runtime.controller;


import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.service.ConversationService;
import com.g2rain.agent.runtime.service.impl.SseEventSink;
import com.g2rain.agent.runtime.vo.ChatConversationVo;
import com.g2rain.agent.runtime.vo.ChatMessageVo;
import com.g2rain.common.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

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
 * <p>
 * 同时提供对话客户端（chat-shell）的会话查询 {@code /chat/**}：只返回当前主体（员工或匿名访客）自己的会话与消息。
 * 管理平台查看整个机构的会话请继续使用 {@code /chat_conversation}、{@code /chat_message} 通用接口；
 * 通用接口不得放入 ANONYMOUS 控制单元。
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

    @GetMapping("/chat/conversation/list")
    @Operation(summary = "我的会话列表", description = "按最近消息时间倒序")
    public Result<List<ChatConversationVo>> listConversations() {
        return Result.success(conversationService.listConversations());
    }

    @GetMapping("/chat/message/list")
    @Operation(summary = "我的会话消息", description = "会话须属于当前主体，按时间正序")
    public Result<List<ChatMessageVo>> listMessages(
        @Parameter(description = "会话标识") @RequestParam String conversationId) {
        return Result.success(conversationService.listMessages(conversationId));
    }

    @DeleteMapping("/chat/conversation/{conversationId}")
    @Operation(summary = "删除我的会话", description = "会话须属于当前主体")
    public Result<Integer> deleteConversation(@Parameter(description = "会话标识") @PathVariable String conversationId) {
        return Result.success(conversationService.deleteConversation(conversationId));
    }
}
