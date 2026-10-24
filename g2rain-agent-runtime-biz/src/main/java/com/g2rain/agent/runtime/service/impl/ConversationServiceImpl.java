package com.g2rain.agent.runtime.service.impl;


import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.TurnStatus;
import com.g2rain.agent.runtime.domain.state.dialogue.MessageType;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.dto.ChatConversationSelectDto;
import com.g2rain.agent.runtime.dto.ChatMessageSelectDto;
import com.g2rain.agent.runtime.dto.ChatProgressSelectDto;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnPipeline;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.engine.TurnResult;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.event.EventPublisher;
import com.g2rain.agent.runtime.event.StepEvents;
import com.g2rain.agent.runtime.event.TurnEvent;
import com.g2rain.agent.runtime.event.TurnEvents;
import com.g2rain.agent.runtime.service.ChatConversationService;
import com.g2rain.agent.runtime.service.ChatMessageService;
import com.g2rain.agent.runtime.service.ChatProgressService;
import com.g2rain.agent.runtime.service.ConversationService;
import com.g2rain.agent.runtime.support.FailureCategory;
import com.g2rain.agent.runtime.support.RedisKeys;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.trace.TraceScope;
import com.g2rain.agent.runtime.vo.ChatConversationVo;
import com.g2rain.agent.runtime.vo.ChatMessageVo;
import com.g2rain.agent.runtime.vo.ChatProgressVo;
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
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
     * 执行步骤的记录与推送。
     */
    private final StepEvents stepEvents;

    /**
     * 会话表服务（对话客户端查询"我的会话"）。
     */
    private final ChatConversationService chatConversationService;

    /**
     * 消息表服务（对话客户端查询"我的会话消息"）。
     */
    private final ChatMessageService chatMessageService;

    /**
     * 执行过程步骤表服务（历史消息附带本轮执行步骤）。
     */
    private final ChatProgressService chatProgressService;

    /**
     * 持会话锁跑一整轮：空会话标识先生成，再加锁、回灌状态并进入流水线。
     *
     * <p>锁内顺序固定为 begin → 流水线（逐步推送执行步骤）→ flush/persist → 结果帧。
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
     * 锁内跑一整轮：begin 后绑定本轮，再进入 {@link #runTurn}。
     *
     * @param request 本轮请求；会话标识已确定
     * @return 本轮结果（已带 traceId）
     */
    private TurnResult runLocked(TurnRequest request) {
        // begin 负责回灌 State、推进栅栏并写入本轮用户消息。
        TurnScope turn = runtimeStateFacade.begin(request);
        TraceScope.bindTask(turn.conversation().activeTaskId());
        // 绑定本轮：各环节经 StepEvents 记录并推送执行步骤，首帧即带会话 ID
        return ScopedValue.where(StepEvents.TURN, turn).call(() -> runTurn(request, turn));
    }

    /**
     * 本轮流水线 → 收尾执行步骤 → flush/persist → 结果帧；在 {@link StepEvents#TURN} 作用域内执行。
     *
     * @param request 本轮请求
     * @param turn    已 begin 的本轮视图
     * @return 本轮结果（已带 traceId）
     */
    private TurnResult runTurn(TurnRequest request, TurnScope turn) {
        // 新会话开局即下发标题，客户端插入会话列表；已有会话不发
        turn.createdTitle().ifPresent(title -> eventPublisher.publish(TurnEvents.title(turn, title)));
        TurnResult result;
        try {
            // 流水线只处理业务编排；结果收口和 State 持久化仍由当前锁内流程负责。
            result = turnPipeline.run(new TurnContext(request, turn));
            result.setTraceId(RuntimeUtils.requireTraceId());
        } catch (RuntimeException ex) {
            log.error("[ConversationService] turn failed conversationId={}",
                turn.conversation().conversationId(), ex);
            // 按失败分类生成文案；识别不了的仍为通用失败
            String errorContent = FailureCategory.of(ex).text();
            String errorCode = RuntimeUtils.businessCode(ex);
            // 先把本轮标失败：节点与任务落为 FAILED，并追加失败回复
            turn.fail(errorContent, errorCode);
            result = TurnResult.fail(
                turn.conversation().conversationId(),
                turn.task().map(TaskMetadata::getTaskId).orElse(null),
                errorContent
            );
            result.setErrorCode(errorCode);
            result.setTraceId(RuntimeUtils.requireTraceId());
        }

        // 出结果前收尾仍在进行的步骤，时间线与结果状态一致
        stepEvents.finishAll(result.getStatus() == TurnStatus.FAILED, result.getErrorCode());
        TurnEvent resultEvent = TurnEvents.result(turn, result.getStatus(), result.getContent(),
            result.getAllowedActions(), result.getSuggestions(), result.getErrorCode());

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
        List<ChatMessageVo> messages = chatMessageService.selectList(messageSelect);
        attachProgress(conversationId, messages);
        return messages;
    }

    /**
     * 按 traceId 把本轮执行步骤挂到最终回复上；排查信息 {@code extra} 不下发。
     *
     * @param conversationId 会话 id
     * @param messages       会话消息
     */
    private void attachProgress(String conversationId, List<ChatMessageVo> messages) {
        ChatProgressSelectDto progressSelect = new ChatProgressSelectDto();
        progressSelect.setOrganId(PrincipalContextHolder.getOrganId());
        progressSelect.setConversationId(conversationId);
        progressSelect.setSorts(List.of("traceId,asc", "seq,asc"));
        List<ChatProgressVo> steps = chatProgressService.selectList(progressSelect);
        steps.forEach(step -> step.setExtra(null));
        Map<String, List<ChatProgressVo>> byTrace = steps.stream()
            .collect(Collectors.groupingBy(ChatProgressVo::getTraceId));
        messages.stream()
            .filter(message -> MessageType.AGENT_REPLY.name().equals(message.getMessageType()))
            .filter(message -> Strings.isBlank(message.getNodeId()) && Strings.isNotBlank(message.getTraceId()))
            .forEach(message -> message.setProgress(byTrace.get(message.getTraceId())));
    }

    @Override
    public int deleteConversation(String conversationId) {
        Asserts.isTrue(Strings.isNotBlank(conversationId), SystemErrorCode.PARAM_REQUIRED, "conversationId");
        ChatConversationSelectDto select = ownerSelect();
        select.setConversationId(conversationId);
        List<ChatConversationVo> owned = chatConversationService.selectSummaryList(select);
        requireOwned(owned);
        return chatConversationService.delete(owned.getFirst().getId());
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
