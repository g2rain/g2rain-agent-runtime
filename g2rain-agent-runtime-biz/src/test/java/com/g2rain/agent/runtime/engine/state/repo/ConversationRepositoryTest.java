package com.g2rain.agent.runtime.engine.state.repo;

import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.dialogue.MessageType;
import com.g2rain.agent.runtime.service.ChatConversationService;
import com.g2rain.agent.runtime.service.ChatMessageService;
import com.g2rain.agent.runtime.vo.ChatMessageVo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConversationRepository} 对话回灌测试。
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

    private static ChatMessageVo message(String messageId, long createdAtMs) {
        ChatMessageVo vo = new ChatMessageVo();
        vo.setMessageId(messageId);
        vo.setRole(DialogueRole.USER.name());
        vo.setMessageType(MessageType.USER_TEXT.name());
        vo.setCreatedAtMs(createdAtMs);
        return vo;
    }
}
