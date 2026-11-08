package com.g2rain.agent.runtime.engine.flow.decision;

import com.g2rain.agent.runtime.domain.decision.IntentResult;
import com.g2rain.agent.runtime.domain.decision.TurnMode;
import com.g2rain.agent.runtime.domain.decision.TurnScene;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型结论校正（场景兜底、写操作确认、无人可选转仅回复）与确认文案。
 *
 * @author alpha
 * @since 2026/10/10
 */
class DefaultTurnDispatcherTest {

    private static IntentResult intent(TurnMode mode, Long agentId, String userReply) {
        IntentResult result = new IntentResult();
        result.setMode(mode);
        result.setAgentId(agentId);
        result.setUserReply(userReply);
        return result;
    }

    @Test
    void missingIntentFallsBackByScene() {
        assertThat(DefaultTurnDispatcher.settle(null, TurnScene.FRESH, false, false)).isEqualTo(TurnMode.START_FRESH);
        assertThat(DefaultTurnDispatcher.settle(null, TurnScene.CONTINUITY, false, false)).isEqualTo(TurnMode.START_FRESH);
        assertThat(DefaultTurnDispatcher.settle(null, TurnScene.ACTIVE_TASK, false, false)).isEqualTo(TurnMode.CONTINUE_TASK);
    }

    @Test
    void lockedAgentWithMissingIntentStartsFresh() {
        assertThat(DefaultTurnDispatcher.settle(null, TurnScene.FRESH, true, false)).isEqualTo(TurnMode.START_FRESH);
    }

    @Test
    void modeOutsideSceneFallsBack() {
        IntentResult continueInFresh = intent(TurnMode.CONTINUE_TASK, null, null);
        assertThat(DefaultTurnDispatcher.settle(continueInFresh, TurnScene.FRESH, false, false)).isEqualTo(TurnMode.START_FRESH);

        IntentResult uncertain = intent(TurnMode.UNCERTAIN, null, null);
        assertThat(DefaultTurnDispatcher.settle(uncertain, TurnScene.FRESH, false, false)).isEqualTo(TurnMode.START_FRESH);
    }

    @Test
    void lockedAgentCannotSwitch() {
        IntentResult switchAgent = intent(TurnMode.SWITCH_AGENT, 1001L, null);
        assertThat(DefaultTurnDispatcher.settle(switchAgent, TurnScene.ACTIVE_TASK, true, false)).isEqualTo(TurnMode.CONTINUE_TASK);
        assertThat(DefaultTurnDispatcher.settle(switchAgent, TurnScene.ACTIVE_TASK, false, false)).isEqualTo(TurnMode.SWITCH_AGENT);
    }

    @Test
    void pendingWriteNeverContinuesOnText() {
        IntentResult resume = intent(TurnMode.CONTINUE_TASK, null, null);
        assertThat(DefaultTurnDispatcher.settle(resume, TurnScene.WRITE_PENDING, false, true)).isEqualTo(TurnMode.UNCERTAIN);
        assertThat(DefaultTurnDispatcher.settle(null, TurnScene.WRITE_PENDING, false, true)).isEqualTo(TurnMode.UNCERTAIN);

        IntentResult cancel = intent(TurnMode.CANCEL_TASK, null, null);
        assertThat(DefaultTurnDispatcher.settle(cancel, TurnScene.WRITE_PENDING, false, true)).isEqualTo(TurnMode.CANCEL_TASK);

        IntentResult replace = intent(TurnMode.REPLACE_TASK, null, null);
        assertThat(DefaultTurnDispatcher.settle(replace, TurnScene.WRITE_PENDING, false, true)).isEqualTo(TurnMode.REPLACE_TASK);
    }

    @Test
    void unmatchedAgentWithReplyOnlyReplies() {
        IntentResult unmatched = intent(TurnMode.START_FRESH, null, "我可以帮您开票或查询发票。");
        assertThat(DefaultTurnDispatcher.settle(unmatched, TurnScene.FRESH, true, false)).isEqualTo(TurnMode.REPLY_ONLY);

        IntentResult noReply = intent(TurnMode.START_FRESH, null, null);
        assertThat(DefaultTurnDispatcher.settle(noReply, TurnScene.FRESH, false, false)).isEqualTo(TurnMode.START_FRESH);
    }

    @Test
    void acceptedModeIsKept() {
        IntentResult withContext = intent(TurnMode.START_WITH_CONTEXT, 1001L, null);
        assertThat(DefaultTurnDispatcher.settle(withContext, TurnScene.CONTINUITY, false, false)).isEqualTo(TurnMode.START_WITH_CONTEXT);

        IntentResult greeting = intent(TurnMode.REPLY_ONLY, null, "您好，有什么可以帮您？");
        assertThat(DefaultTurnDispatcher.settle(greeting, TurnScene.FRESH, false, false)).isEqualTo(TurnMode.REPLY_ONLY);
    }

    @Test
    void confirmationUsesCheckpointSpecificText() {
        assertThat(DefaultTurnDispatcher.confirmation(scopeWith(CheckpointType.APPROVAL)).getUserReply())
            .isEqualTo(RuntimeMessage.ROUTE_UNCERTAIN.text());
        assertThat(DefaultTurnDispatcher.confirmation(scopeWith(CheckpointType.VERIFY)).getUserReply())
            .isEqualTo(RuntimeMessage.VERIFY_ASK.text());

        IntentResult confirmation = DefaultTurnDispatcher.confirmation(scopeWith(CheckpointType.APPROVAL));
        assertThat(confirmation.getMode()).isEqualTo(TurnMode.UNCERTAIN);
        assertThat(confirmation.getAgentId()).isNull();
    }

    private static TurnScope scopeWith(CheckpointType type) {
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setType(type);
        TurnScope turn = mock(TurnScope.class);
        when(turn.checkpoint()).thenReturn(Optional.of(checkpoint));
        return turn;
    }
}
