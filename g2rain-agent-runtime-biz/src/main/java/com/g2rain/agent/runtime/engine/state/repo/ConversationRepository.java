package com.g2rain.agent.runtime.engine.state.repo;

import com.g2rain.agent.runtime.converter.ChatConversationConverter;
import com.g2rain.agent.runtime.converter.ChatMessageConverter;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.dialogue.MessageType;
import com.g2rain.agent.runtime.dto.ChatConversationDto;
import com.g2rain.agent.runtime.dto.ChatConversationSelectDto;
import com.g2rain.agent.runtime.dto.ChatMessageDto;
import com.g2rain.agent.runtime.dto.ChatMessageSelectDto;
import com.g2rain.agent.runtime.service.ChatConversationService;
import com.g2rain.agent.runtime.service.ChatMessageService;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.vo.ChatConversationVo;
import com.g2rain.agent.runtime.vo.ChatMessageVo;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Moments;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话仓储：{@code chat_conversation}、{@code chat_message} 的读写。
 *
 * @author alpha
 * @since 2026/10/12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationRepository {

    /**
     * 会话标题字符上限：取首条用户消息的前 64 个字符，会话列表与观测页展示用；列宽另留余量。
     */
    private static final int CONV_TITLE_MAX = 64;

    /**
     * {@code chat_message.channel} 上限。
     */
    private static final int MSG_CHANNEL_MAX = 32;

    /**
     * {@code chat_message.model} 上限。
     */
    private static final int MSG_MODEL_MAX = 128;

    /**
     * 会话主表服务。
     */
    private final ChatConversationService chatConversationService;

    /**
     * 交互消息表服务。
     */
    private final ChatMessageService chatMessageService;

    /**
     * 按会话 id 读主表行。
     *
     * @param conversationId 会话 id
     * @return 行；没有为 {@code null}
     */
    public @Nullable ChatConversationVo find(String conversationId) {
        ChatConversationSelectDto query = new ChatConversationSelectDto();
        query.setConversationId(conversationId);
        List<ChatConversationVo> list = chatConversationService.selectList(query);
        return list.isEmpty() ? null : list.getFirst();
    }

    /**
     * 新建会话行；已存在则跳过。失败向上抛。
     *
     * @param conversationId 会话 id
     * @param userId         所属用户
     * @param titleHint      可选标题
     * @return 新建时写入的标题；已存在或无标题时为空
     */
    public @Nullable String create(String conversationId, Long userId, @Nullable String titleHint) {
        if (Objects.nonNull(find(conversationId))) {
            return null;
        }
        String title = Strings.isNotBlank(titleHint) ? RuntimeUtils.clip(titleHint.trim(), CONV_TITLE_MAX) : null;
        ChatConversationDto dto = new ChatConversationDto();
        dto.setConversationId(conversationId);
        dto.setOrganId(PrincipalContextHolder.getOrganId());
        dto.setUserId(userId);
        dto.setStateVersion(0L);
        dto.setTitle(title);
        chatConversationService.save(dto);
        return title;
    }

    /**
     * 从 {@code chat_conversation.session_shared} 回灌会话共享值。
     *
     * @param conversationId 会话 id
     * @return 会话记忆（已确认、可跨任务继承的字段）；没有为不可变空 Map
     */
    public Map<String, Sourced<?>> loadSessionShared(String conversationId) {
        ChatConversationVo row = find(conversationId);
        if (Objects.isNull(row) || Strings.isBlank(row.getSessionShared())) {
            return Map.of();
        }

        return RepoSupport.JSON.str2obj(row.getSessionShared(), RepoSupport.SHARED_MAP);
    }

    /**
     * 从 {@code chat_message} 回灌会话对话（时间正序）。
     *
     * @param conversationId 会话 id
     * @param limit          最多条数
     * @return 消息
     */
    public List<DialogueMessage> loadDialogue(String conversationId, int limit) {
        ChatMessageSelectDto query = new ChatMessageSelectDto();
        query.setConversationId(conversationId);
        List<ChatMessageVo> rows = chatMessageService.selectList(query);
        if (Collections.isEmpty(rows)) {
            return List.of();
        }
        int from = Math.max(0, rows.size() - limit);
        return rows.subList(from, rows.size()).stream().map(this::toDialogue).toList();
    }

    /**
     * 写入一条对话；入口登记和轮末增量落库都会调用此方法，失败直接向上抛出。
     *
     * @param conversationId 会话 id
     * @param taskId         任务 id；可空
     * @param message        消息
     * @param channel        渠道
     */
    public void insertMessage(String conversationId, @Nullable String taskId, DialogueMessage message, @Nullable String channel) {
        String messageId = Strings.isNotBlank(message.getMessageId()) ? message.getMessageId() : RuntimeUtils.uuid();
        ChatMessageDto dto = new ChatMessageDto();
        dto.setOrganId(PrincipalContextHolder.getOrganId());
        dto.setConversationId(conversationId);
        dto.setTaskId(taskId);
        dto.setNodeId(message.getNodeId());
        dto.setRound(message.getRound());
        dto.setMessageId(messageId);
        dto.setRole(Objects.requireNonNull(message.getRole()).name());
        MessageType type = Objects.nonNull(message.getType()) ? message.getType() : MessageType.from(message.getRole());
        dto.setMessageType(type.name());
        dto.setContent(message.getText());
        dto.setCreatedAtMs(message.getCreatedAt());
        dto.setElapsedMs(message.getElapsedMs());
        if (Strings.isNotBlank(channel)) {
            dto.setChannel(RuntimeUtils.clip(channel.trim(), MSG_CHANNEL_MAX));
        }
        if (Strings.isNotBlank(message.getTraceId())) {
            dto.setTraceId(RuntimeUtils.clip(message.getTraceId(), RepoSupport.TRACE_MAX));
        }
        dto.setModel(RuntimeUtils.clip(message.getModel(), MSG_MODEL_MAX));
        chatMessageService.save(dto);
        touch(conversationId);
    }

    /**
     * 推进会话栅栏版本并写会话共享值与活动任务（CAS）。
     *
     * @param conversationId 会话 id
     * @param version        本轮栅栏版本
     * @param sessionShared  会话共享值 JSON；可空
     * @param activeTaskId   活动任务 id；可空
     * @return 影响行数；0 表示被栅栏挡下或会话不存在
     */
    public int advanceVersion(String conversationId, long version, @Nullable String sessionShared,
                              @Nullable String activeTaskId) {
        return chatConversationService.advanceStateVersion(conversationId, version, sessionShared, activeTaskId);
    }

    /**
     * 回填触发开启任务的用户消息的 task_id：该消息入库时任务尚未创建。
     *
     * @param messageId 触发消息 id；可空
     * @param taskId    任务 id
     */
    public void bindTask(@Nullable String messageId, String taskId) {
        if (Strings.isBlank(messageId)) {
            return;
        }

        ChatMessageSelectDto query = new ChatMessageSelectDto();
        query.setMessageId(messageId);
        chatMessageService.selectList(query).stream()
            .filter(vo -> Strings.isBlank(vo.getTaskId()))
            .forEach(vo -> {
                ChatMessageDto dto = ChatMessageConverter.INSTANCE.vo2dto(vo);
                dto.setTaskId(taskId);
                chatMessageService.save(dto);
            });
    }

    /**
     * 刷新会话最后消息时间。
     *
     * @param conversationId 会话 id
     */
    private void touch(String conversationId) {
        ChatConversationVo existing = find(conversationId);
        if (Objects.isNull(existing)) {
            return;
        }

        var dto = ChatConversationConverter.INSTANCE.vo2dto(existing);
        dto.setLastMessageTime(Moments.now());
        chatConversationService.save(dto);
    }

    /**
     * {@code chat_message} 行转对话消息。
     *
     * @param vo 表行
     * @return 对话消息
     */
    private DialogueMessage toDialogue(ChatMessageVo vo) {
        DialogueMessage message = new DialogueMessage();
        if (Strings.isNotBlank(vo.getRole())) {
            message.setRole(DialogueRole.valueOf(vo.getRole()));
        }
        message.setText(vo.getContent());
        message.setMessageId(vo.getMessageId());
        message.setTraceId(vo.getTraceId());
        if (Strings.isNotBlank(vo.getMessageType())) {
            message.setType(MessageType.valueOf(vo.getMessageType()));
        }
        message.setTaskId(vo.getTaskId());
        message.setNodeId(vo.getNodeId());
        message.setRound(vo.getRound());
        message.setModel(vo.getModel());
        message.setCreatedAt(Objects.requireNonNullElse(vo.getCreatedAtMs(), 0L));
        message.setElapsedMs(vo.getElapsedMs());
        return message;
    }
}
