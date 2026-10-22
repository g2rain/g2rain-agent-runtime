package com.g2rain.agent.runtime.service.impl;


import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.dto.ChatConversationSelectDto;
import com.g2rain.agent.runtime.dto.ChatMessageSelectDto;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnPipeline;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.engine.TurnResult;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.agent.runtime.event.EventPublisher;
import com.g2rain.agent.runtime.event.TurnEvent;
import com.g2rain.agent.runtime.event.TurnEvents;
import com.g2rain.agent.runtime.service.ChatConversationService;
import com.g2rain.agent.runtime.service.ChatMessageService;
import com.g2rain.agent.runtime.service.ConversationService;
import com.g2rain.agent.runtime.support.RedisKeys;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.trace.TraceScope;
import com.g2rain.agent.runtime.vo.ChatConversationVo;
import com.g2rain.agent.runtime.vo.ChatMessageVo;
import com.g2rain.common.exception.SystemErrorCode;
import com.g2rain.common.utils.Asserts;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContextHolder;
import com.g2rain.data.redis.DistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 会话请求的同步编排入口。
 *
 * <p>在同一会话锁内完成状态回灌、本轮流水线、Redis 刷新、持久化和结束事件发布。
 * 流水线异常收成统一失败结果，避免调用方收到未归类的运行时异常。
 * 本类不推 SSE 连接，事件经 {@link EventPublisher} 扇出。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    /**
     * 同一会话串行锁，防止两个请求同时修改同一份运行状态。
     */
    private final DistributedLock distributedLock;

    /**
     * State 的统一读写门面，负责本轮开始、刷新和持久化。
     */
    private final RuntimeStateFacade runtimeStateFacade;

    /**
     * 执行入口、决策、图运行和结果收口组成的本轮流水线。
     */
    private final TurnPipeline turnPipeline;

    /**
     * 向客户端发布本轮生命周期和结果事件的发布器。
     */
    private final EventPublisher eventPublisher;

    /**
     * 会话表服务（对话客户端查询"我的会话"）。
     */
    private final ChatConversationService chatConversationService;

    /**
     * 消息表服务（对话客户端查询"我的会话消息"）。
     */
    private final ChatMessageService chatMessageService;

    /**
     * 持会话锁跑一整轮：空会话标识先生成，再加锁、回灌状态并进入流水线。
     *
     * <p>锁内顺序固定为 begin → 首条过程提示 → 流水线 → flush/persist → 结果帧。
     * 编排异常收成失败结果并仍发结束事件；持久化失败只记日志，不改变已算出的本轮结果。</p>
     *
     * @param request 本轮请求；会话标识可空，空则现场生成
     * @return 本轮结果（已带 traceId）
     */
    @Override
    public TurnResult send(TurnRequest request) {
        // 空会话由服务端创建；之后整个请求都使用同一个会话锁和 traceId。
        if (Strings.isBlank(request.getConversationId())) {
            request.setConversationId(RuntimeUtils.uuid());
        }

        String lockKey = RedisKeys.LOCK.format(request.getConversationId());
        // 轨迹作用域覆盖加锁、编排与落盘，本轮所有日志都带会话与任务 ID
        try (TraceScope ignored = TraceScope.turn(request.getConversationId())) {
            return distributedLock.waitLockWithWatchdog(lockKey, Boolean.FALSE, () ->
                runLocked(request)
            );
        }
    }

    /**
     * 锁内跑一整轮：begin → 首条过程提示 → 流水线 → flush/persist → 结果帧。
     *
     * @param request 本轮请求；会话标识已确定
     * @return 本轮结果（已带 traceId）
     */
    private TurnResult runLocked(TurnRequest request) {
        // begin 负责回灌 State、推进栅栏并写入本轮用户消息。
        TurnScope turn = runtimeStateFacade.begin(request);
        TraceScope.bindTask(turn.conversation().activeTaskId());
        // 锁内先发一条过程提示，客户端从首帧即可拿到会话 ID
        eventPublisher.publish(TurnEvents.progress(turn, RuntimeMessage.STATUS_WORKING.text()));

        TurnResult result;
        try {
            // 流水线只处理业务编排；结果收口和 State 持久化仍由当前锁内流程负责。
            result = turnPipeline.run(new TurnContext(request, turn));
            result.setTraceId(RuntimeUtils.requireTraceId());
        } catch (RuntimeException ex) {
            log.error("[ConversationService] turn failed conversationId={}",
                turn.conversation().conversationId(), ex);
            String errorContent = RuntimeMessage.ERROR_GENERIC.text();
            // 先把本轮标失败：节点与任务落为 FAILED，并追加失败回复
            turn.fail(errorContent, RuntimeUtils.businessCode(ex));
            result = TurnResult.fail(
                turn.conversation().conversationId(),
                turn.task().map(TaskMetadata::getTaskId).orElse(null),
                errorContent
            );
            result.setTraceId(RuntimeUtils.requireTraceId());
        }

        TurnEvent resultEvent = TurnEvents.result(turn, result.getStatus(), result.getContent(),
            result.getAllowedActions(), result.getSuggestions());

        try {
            // 先把本轮改动刷新到 Redis，再以 Redis 当前状态为准落 MySQL。
            runtimeStateFacade.flush(turn);
            runtimeStateFacade.persist(turn);
        } catch (RuntimeException ex) {
            log.error("[ConversationService] persist failed conversationId={}",
                turn.conversation().conversationId(), ex);
            // 落库失败只记日志，result 和已构造的结果帧仍按内存状态下发
        }

        // persist 失败仍发已构造的结果帧，避免客户端一直等收口
        eventPublisher.publish(resultEvent);
        return result;
    }

    @Override
    public List<ChatConversationVo> listConversations() {
        ChatConversationSelectDto select = ownerSelect();
        select.setSorts(List.of("lastMessageTime,desc"));
        return chatConversationService.selectSummaryList(select);
    }

    @Override
    public List<ChatMessageVo> listMessages(String conversationId) {
        Asserts.isTrue(Strings.isNotBlank(conversationId), SystemErrorCode.PARAM_REQUIRED, "conversationId");
        ChatConversationSelectDto select = ownerSelect();
        select.setConversationId(conversationId);
        requireOwned(chatConversationService.selectSummaryList(select));

        ChatMessageSelectDto messageSelect = new ChatMessageSelectDto();
        messageSelect.setOrganId(PrincipalContextHolder.getOrganId());
        messageSelect.setConversationId(conversationId);
        messageSelect.setSorts(List.of("createdAtMs,asc", "id,asc"));
        return chatMessageService.selectList(messageSelect);
    }

    @Override
    public int deleteConversation(Long id) {
        Asserts.isTrue(Objects.nonNull(id) && id > 0, SystemErrorCode.PARAM_REQUIRED, "id");
        ChatConversationSelectDto select = ownerSelect();
        select.setId(id);
        requireOwned(chatConversationService.selectSummaryList(select));
        return chatConversationService.delete(id);
    }

    /**
     * 当前主体的会话查询条件；主体不完整时拒绝。
     */
    private static ChatConversationSelectDto ownerSelect() {
        Long organId = PrincipalContextHolder.getOrganId();
        Long userId = PrincipalContextHolder.getUserId();
        Asserts.isTrue(Objects.nonNull(organId) && Objects.nonNull(userId), SystemErrorCode.UNAUTHENTICATED);

        ChatConversationSelectDto select = new ChatConversationSelectDto();
        select.setOrganId(organId);
        select.setUserId(userId);
        return select;
    }

    /**
     * 会话不存在或不属于当前主体时统一返回"无权访问该会话"，不区分两种情况。
     */
    private static void requireOwned(List<ChatConversationVo> owned) {
        Asserts.isTrue(Collections.isNotEmpty(owned), RuntimeErrorCode.CONVERSATION_FORBIDDEN);
    }
}
