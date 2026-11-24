package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.dialogue.MessageType;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.spi.Attachment;
import com.g2rain.agent.runtime.spi.AttachmentType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本轮回复附件：添加、上限与最终回复挂载。
 *
 * @author alpha
 * @since 2026/11/24
 */
class AttachmentTurnScopeTest {

    private static final String MARKER = "UNIQUE_ATTACHMENT_BODY_MARKER_xyz";

    @Test
    void attach_keepsOrder() {
        DefaultTurnScope turn = turn(5, 500000);
        turn.attach(report("a", "one"));
        turn.attach(report("b", "two"));

        assertEquals(List.of("a", "b"),
            turn.attachments().stream().map(Attachment::title).toList());
    }

    @Test
    void attach_rejectsWhenMaxPerTurnExceeded() {
        DefaultTurnScope turn = turn(1, 500000);
        turn.attach(report("a", "one"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> turn.attach(report("b", "two")));

        assertTrue(error.getMessage().contains("max-per-turn"));
        assertTrue(error.getMessage().contains("1"));
        assertFalse(error.getMessage().contains("two"));
    }

    @Test
    void attach_rejectsWhenMaxCharsExceeded() {
        DefaultTurnScope turn = turn(5, 8);
        String body = MARKER + "0123456789";

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> turn.attach(report("long", body)));

        assertTrue(error.getMessage().contains("max-chars"));
        assertTrue(error.getMessage().contains("8"));
        assertFalse(error.getMessage().contains(MARKER));
        assertFalse(error.getMessage().contains(body));
    }

    @Test
    void appendFinalReply_copiesAttachments() {
        DefaultTurnScope turn = turn(5, 500000);
        turn.attach(report("r1", "body"));
        DialogueMessage reply = new DialogueMessage(DialogueRole.AGENT, "done");
        reply.setType(MessageType.AGENT_REPLY);
        reply.setMessageId("m1");
        turn.append(reply);

        assertEquals(1, reply.getAttachments().size());
        assertEquals("r1", reply.getAttachments().getFirst().title());
        assertEquals(reply, turn.reply().orElseThrow());
    }

    @Test
    void appendFinalReply_withoutAttachments_leavesNull() {
        DefaultTurnScope turn = turn(5, 500000);
        DialogueMessage reply = new DialogueMessage(DialogueRole.AGENT, "done");
        reply.setType(MessageType.AGENT_REPLY);
        reply.setMessageId("m1");
        turn.append(reply);

        assertNull(reply.getAttachments());
    }

    @Test
    void abortWithoutReply_doesNotMountAttachmentsOnDialogue() {
        DefaultTurnScope turn = turn(5, 500000);
        turn.attach(report("r1", "body"));
        turn.abort("failed", null);

        assertTrue(turn.reply().isEmpty());
        for (DialogueMessage message : turn.allDialogue()) {
            assertNull(message.getAttachments());
        }
    }

    private static DefaultTurnScope turn(int maxPerTurn, int maxChars) {
        ConversationFence fence = new ConversationFence("c1", "u1", null, 1L, "trace", 1L);
        return new DefaultTurnScope("1", "u1", "trace", fence, List.of(), List.of(), new HashMap<>(),
            null, new TaskGraph(), null, new HashMap<>(), null, new HashMap<>(), 60, maxPerTurn, maxChars);
    }

    private static Attachment report(String title, String content) {
        return new Attachment(AttachmentType.REPORT, title, content);
    }
}
