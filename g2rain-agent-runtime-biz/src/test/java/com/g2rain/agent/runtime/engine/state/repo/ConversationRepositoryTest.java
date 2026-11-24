package com.g2rain.agent.runtime.engine.state.repo;

import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.dialogue.MessageType;
import com.g2rain.agent.runtime.dto.ChatMessageDto;
import com.g2rain.agent.runtime.service.ChatConversationService;
import com.g2rain.agent.runtime.service.ChatMessageService;
import com.g2rain.agent.runtime.spi.Attachment;
import com.g2rain.agent.runtime.spi.AttachmentType;
import com.g2rain.agent.runtime.vo.ChatMessageVo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConversationRepository} 对话回灌与会话行专用更新测试。
 *
 * @author alpha
 * @since 2026/11/16
 */
class ConversationRepositoryTest {

    private static final String CONVERSATION_ID = "c1";

    private static final int LIMIT = 3;

    @Test
    void loadDialogue_shouldReturnLatestInChronologicalOrder() {
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        when(chatMessageService.selectLatest(CONVERSATION_ID, LIMIT))
            .thenReturn(List.of(message("m3", 300L), message("m2", 200L), message("m1", 100L)));
        ConversationRepository repository = new ConversationRepository(mock(ChatConversationService.class), chatMessageService);

        List<DialogueMessage> dialogue = repository.loadDialogue(CONVERSATION_ID, LIMIT);

        verify(chatMessageService).selectLatest(CONVERSATION_ID, LIMIT);
        assertEquals(List.of("m1", "m2", "m3"), dialogue.stream().map(DialogueMessage::getMessageId).toList());
    }

    /** U5：活动任务相同 → 仍走专用更新，不读行、不 save（SQL 侧按条件跳过）。 */
    @Test
    void u5_bindActiveTask_sameTask_usesDedicatedUpdate() {
        ChatConversationService conversationService = mock(ChatConversationService.class);
        ConversationRepository repository = new ConversationRepository(conversationService, mock(ChatMessageService.class));

        repository.bindActiveTask(CONVERSATION_ID, "t1");

        verify(conversationService).bindActiveTask(CONVERSATION_ID, "t1");
        verify(conversationService, never()).selectList(any());
        verify(conversationService, never()).save(any());
    }

    /** U6：活动任务不同 → 专用更新，不读行、不 save。 */
    @Test
    void u6_bindActiveTask_differentTask_usesDedicatedUpdate() {
        ChatConversationService conversationService = mock(ChatConversationService.class);
        ConversationRepository repository = new ConversationRepository(conversationService, mock(ChatMessageService.class));

        repository.bindActiveTask(CONVERSATION_ID, "t1");

        verify(conversationService).bindActiveTask(CONVERSATION_ID, "t1");
        verify(conversationService, never()).selectList(any());
        verify(conversationService, never()).save(any());
    }

    /** R3：insertMessage → touchLastMessage；不 save、不读会话行。 */
    @Test
    void r3_insertMessage_touchesLastMessageOnly() {
        ChatConversationService conversationService = mock(ChatConversationService.class);
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ConversationRepository repository = new ConversationRepository(conversationService, chatMessageService);
        DialogueMessage message = new DialogueMessage();
        message.setRole(DialogueRole.USER);
        message.setText("hi");
        message.setMessageId("m1");
        message.setCreatedAt(1L);

        repository.insertMessage(CONVERSATION_ID, "t1", message, null);

        ArgumentCaptor<ChatMessageDto> messageCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(chatMessageService).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getMessageId()).isEqualTo("m1");
        verify(conversationService).touchLastMessage(eq(CONVERSATION_ID), any(LocalDateTime.class));
        verify(conversationService, never()).selectList(any());
        verify(conversationService, never()).save(any());
    }

    /** R4：bindActiveTask → Service.bindActiveTask；不读行、不 save。 */
    @Test
    void r4_bindActiveTask_callsServiceDirectly() {
        ChatConversationService conversationService = mock(ChatConversationService.class);
        ConversationRepository repository = new ConversationRepository(conversationService, mock(ChatMessageService.class));

        repository.bindActiveTask(CONVERSATION_ID, "t2");

        verify(conversationService).bindActiveTask(CONVERSATION_ID, "t2");
        verify(conversationService, never()).selectList(any());
        verify(conversationService, never()).save(any());
    }

    @Test
    void insertMessage_withAttachments_writesJson() {
        ChatConversationService conversationService = mock(ChatConversationService.class);
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ConversationRepository repository = new ConversationRepository(conversationService, chatMessageService);
        DialogueMessage message = new DialogueMessage(DialogueRole.AGENT, "done");
        message.setMessageId("m1");
        message.setType(MessageType.AGENT_REPLY);
        message.setCreatedAt(1L);
        message.setAttachments(List.of(new Attachment(AttachmentType.REPORT, "示例报告", "# 正文")));

        repository.insertMessage(CONVERSATION_ID, "t1", message, null);

        ArgumentCaptor<ChatMessageDto> messageCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(chatMessageService).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getAttachments()).contains("示例报告");
        assertThat(messageCaptor.getValue().getAttachments()).contains("REPORT");
    }

    @Test
    void insertMessage_withoutAttachments_writesNull() {
        ChatConversationService conversationService = mock(ChatConversationService.class);
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ConversationRepository repository = new ConversationRepository(conversationService, chatMessageService);
        DialogueMessage message = new DialogueMessage(DialogueRole.AGENT, "done");
        message.setMessageId("m1");
        message.setType(MessageType.AGENT_REPLY);
        message.setCreatedAt(1L);

        repository.insertMessage(CONVERSATION_ID, "t1", message, null);

        ArgumentCaptor<ChatMessageDto> messageCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(chatMessageService).save(messageCaptor.capture());
        assertNull(messageCaptor.getValue().getAttachments());
    }

    private static ChatMessageVo message(String messageId, long createdAtMs) {
        ChatMessageVo vo = new ChatMessageVo();
        vo.setMessageId(messageId);
        vo.setRole(DialogueRole.USER.name());
        vo.setMessageType(MessageType.USER_TEXT.name());
        vo.setCreatedAtMs(createdAtMs);
        return vo;
    }
}
