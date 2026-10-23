package com.g2rain.agent.runtime.service.impl;

import com.g2rain.agent.runtime.dto.ChatConversationSelectDto;
import com.g2rain.agent.runtime.engine.TurnPipeline;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.event.EventPublisher;
import com.g2rain.agent.runtime.event.StepEvents;
import com.g2rain.agent.runtime.dto.ChatMessageSelectDto;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.service.ChatConversationService;
import com.g2rain.agent.runtime.service.ChatMessageService;
import com.g2rain.agent.runtime.service.ChatProgressService;
import com.g2rain.agent.runtime.vo.ChatConversationVo;
import com.g2rain.agent.runtime.vo.ChatMessageVo;
import com.g2rain.agent.runtime.vo.ChatProgressVo;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.exception.SystemErrorCode;
import com.g2rain.common.model.SortItem;
import com.g2rain.common.web.PrincipalContext;
import com.g2rain.common.web.PrincipalContextHolder;
import com.g2rain.data.redis.DistributedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceImplChatTest {

    private ConversationServiceImpl service;
    private ChatConversationService chatConversationService;
    private ChatMessageService chatMessageService;
    private ChatProgressService chatProgressService;

    @BeforeEach
    void setUp() {
        chatConversationService = mock(ChatConversationService.class);
        chatMessageService = mock(ChatMessageService.class);
        chatProgressService = mock(ChatProgressService.class);
        service = new ConversationServiceImpl(mock(DistributedLock.class), mock(RuntimeStateFacade.class),
            mock(TurnPipeline.class), mock(EventPublisher.class), mock(StepEvents.class),
            chatConversationService, chatMessageService, chatProgressService);
    }

    @Test
    void listConversations_shouldForceCurrentOwner() {
        runAs(77L, () -> service.listConversations());

        ArgumentCaptor<ChatConversationSelectDto> captor = ArgumentCaptor.forClass(ChatConversationSelectDto.class);
        verify(chatConversationService).selectSummaryList(captor.capture());
        assertEquals(10001L, captor.getValue().getOrganId());
        assertEquals(77L, captor.getValue().getUserId());
        SortItem sort = captor.getValue().getSafeSorts().getFirst();
        assertEquals("lastMessageTime", sort.getColumn());
        assertEquals("DESC", sort.getDirection());
    }

    @Test
    void listMessages_ofOthersConversation_shouldBeForbidden() {
        when(chatConversationService.selectSummaryList(any())).thenReturn(List.of());

        runAs(77L, () -> {
            BusinessException ex = assertThrows(BusinessException.class, () -> service.listMessages("c-other"));
            assertEquals(RuntimeErrorCode.CONVERSATION_FORBIDDEN.code(), ex.getErrorCode());
        });
        verify(chatMessageService, never()).selectList(any());
    }

    @Test
    void listMessages_ofOwnConversation_shouldQueryByConversation() {
        when(chatConversationService.selectSummaryList(any())).thenReturn(List.of(new ChatConversationVo()));

        runAs(77L, () -> service.listMessages("c-own"));

        ArgumentCaptor<ChatMessageSelectDto> captor = ArgumentCaptor.forClass(ChatMessageSelectDto.class);
        verify(chatMessageService).selectList(captor.capture());
        assertEquals("c-own", captor.getValue().getConversationId());
        assertEquals(10001L, captor.getValue().getOrganId());
    }

    @Test
    void listMessages_shouldAttachProgressToFinalReplyWithoutExtra() {
        when(chatConversationService.selectSummaryList(any())).thenReturn(List.of(new ChatConversationVo()));
        ChatMessageVo user = message("USER", "USER_TEXT", null, "t-1");
        ChatMessageVo reply = message("AGENT", "AGENT_REPLY", null, "t-1");
        ChatMessageVo inner = message("AGENT", "AGENT_REPLY", "n-1", "t-1");
        when(chatMessageService.selectList(any())).thenReturn(List.of(user, reply, inner));
        ChatProgressVo step = new ChatProgressVo();
        step.setTraceId("t-1");
        step.setStepId("s-1");
        step.setExtra("{\"model\":\"secret\"}");
        when(chatProgressService.selectList(any())).thenReturn(List.of(step));

        runAs(77L, () -> service.listMessages("c-own"));

        assertEquals(List.of(step), reply.getProgress());
        assertNull(step.getExtra());
        assertNull(user.getProgress());
        assertNull(inner.getProgress());
    }

    private static ChatMessageVo message(String role, String type, String nodeId, String traceId) {
        ChatMessageVo vo = new ChatMessageVo();
        vo.setRole(role);
        vo.setMessageType(type);
        vo.setNodeId(nodeId);
        vo.setTraceId(traceId);
        return vo;
    }

    @Test
    void deleteConversation_ofOthers_shouldBeForbidden() {
        when(chatConversationService.selectSummaryList(any())).thenReturn(List.of());

        runAs(77L, () -> assertThrows(BusinessException.class, () -> service.deleteConversation("c-other")));
        verify(chatConversationService, never()).delete(anyLong());
    }

    @Test
    void deleteConversation_ofOwn_shouldDelete() {
        ChatConversationVo own = new ChatConversationVo();
        own.setId(9L);
        when(chatConversationService.selectSummaryList(any())).thenReturn(List.of(own));

        runAs(77L, () -> service.deleteConversation("c-own"));

        verify(chatConversationService).delete(9L);
    }

    @Test
    void withoutUser_shouldBeUnauthenticated() {
        runAs(null, () -> {
            BusinessException ex = assertThrows(BusinessException.class, () -> service.listConversations());
            assertEquals(SystemErrorCode.UNAUTHENTICATED.code(), ex.getErrorCode());
        });
    }

    private static void runAs(Long userId, Runnable action) {
        PrincipalContext context = PrincipalContext.of();
        context.setApplicationId(1001L);
        context.setOrganId(10001L);
        context.setUserId(userId);
        PrincipalContextHolder.runWith(context, action);
    }
}
