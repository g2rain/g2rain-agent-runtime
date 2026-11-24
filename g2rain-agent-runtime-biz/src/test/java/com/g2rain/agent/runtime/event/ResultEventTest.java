package com.g2rain.agent.runtime.event;

import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.TurnStatus;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.dialogue.MessageType;
import com.g2rain.agent.runtime.domain.state.task.HaltReason;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.spi.Attachment;
import com.g2rain.agent.runtime.spi.AttachmentType;
import com.g2rain.common.json.JsonCodecFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RESULT 事件停顿原因与附件下发验收。
 *
 * @author alpha
 * @since 2026/11/23
 */
class ResultEventTest {

    @Test
    void waitingFollowUp_includesHaltReason() {
        TurnEvent event = TurnEvents.result(turn(HaltReason.AMBIGUOUS, null), TurnStatus.WAITING,
            "请选择", List.of(ResumeAction.REPLY, ResumeAction.CANCEL), List.of("A"), null);

        assertEquals(TurnStatus.WAITING, event.status());
        assertEquals(HaltReason.AMBIGUOUS.name(), event.haltReason());
        assertEquals(List.of(ResumeAction.REPLY, ResumeAction.CANCEL), event.actions());
    }

    @Test
    void waitingApproval_includesHaltReason() {
        TurnEvent event = TurnEvents.result(turn(HaltReason.APPROVAL, null), TurnStatus.WAITING,
            "请确认", List.of(ResumeAction.CONFIRM, ResumeAction.REJECT, ResumeAction.CANCEL), null, null);

        assertEquals(HaltReason.APPROVAL.name(), event.haltReason());
    }

    @Test
    void done_omitsHaltReason() {
        TurnEvent event = TurnEvents.result(turn(null, null), TurnStatus.DONE, "完成", null, null, null);

        assertNull(event.haltReason());
        assertTrueEmpty(event.actions());
    }

    @Test
    void failed_omitsHaltReason() {
        TurnEvent event = TurnEvents.result(turn(HaltReason.APPROVAL, null), TurnStatus.FAILED,
            "失败", null, null, "agent_runtime.x");

        assertNull(event.haltReason());
        assertEquals("agent_runtime.x", event.errorCode());
    }

    @Test
    void result_withoutAttachments_omitsFieldInJson() {
        TurnEvent event = TurnEvents.result(turn(null, reply(null)), TurnStatus.DONE, "完成", null, null, null);

        assertNull(event.attachments());
        String json = JsonCodecFactory.instance().obj2str(event);
        assertFalse(json.contains("attachments"));
    }

    @Test
    void result_withAttachments_includesField() {
        Attachment attachment = new Attachment(AttachmentType.REPORT, "示例报告", "# 正文");
        TurnEvent event = TurnEvents.result(turn(null, reply(List.of(attachment))), TurnStatus.DONE,
            "完成", null, null, null);

        assertEquals(1, event.attachments().size());
        assertEquals("示例报告", event.attachments().getFirst().title());
        String json = JsonCodecFactory.instance().obj2str(event);
        assertTrue(json.contains("attachments"));
        assertTrue(json.contains("REPORT"));
    }

    private static TurnScope turn(HaltReason haltReason, DialogueMessage reply) {
        TurnScope turn = mock(TurnScope.class);
        when(turn.conversation()).thenReturn(new ConversationFence("c1", "u1", null, 1L, "trace-1", 1L));
        when(turn.traceId()).thenReturn("trace-1");
        when(turn.reply()).thenReturn(Optional.ofNullable(reply));
        if (haltReason == null) {
            when(turn.checkpoint()).thenReturn(Optional.empty());
        } else {
            TaskCheckpoint checkpoint = new TaskCheckpoint();
            checkpoint.setHaltReason(haltReason);
            when(turn.checkpoint()).thenReturn(Optional.of(checkpoint));
        }
        return turn;
    }

    private static DialogueMessage reply(List<Attachment> attachments) {
        DialogueMessage message = new DialogueMessage(DialogueRole.AGENT, "done");
        message.setType(MessageType.AGENT_REPLY);
        message.setCreatedAt(100L);
        message.setElapsedMs(10);
        message.setAttachments(attachments);
        return message;
    }

    private static void assertTrueEmpty(List<ResumeAction> actions) {
        assertEquals(List.of(), actions);
    }
}
