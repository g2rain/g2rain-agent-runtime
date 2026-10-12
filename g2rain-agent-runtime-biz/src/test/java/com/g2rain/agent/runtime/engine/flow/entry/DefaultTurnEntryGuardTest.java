package com.g2rain.agent.runtime.engine.flow.entry;

import com.g2rain.agent.runtime.client.AgentProfiles;
import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.entry.EntryResult;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnRequest;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 挂起任务的入口分流：补参直接续跑，审批与回查只认显式动作。
 *
 * @author alpha
 * @since 2026/10/10
 */
class DefaultTurnEntryGuardTest {

    private final DefaultTurnEntryGuard guard =
        new DefaultTurnEntryGuard(mock(AgentProfiles.class), mock(RuntimeProperties.class));

    @Test
    void followUpReplyContinuesWithoutClassification() {
        EntryResult result = guard.guard(context(CheckpointType.FOLLOW_UP, "上海"));

        assertThat(result.forceContinue()).isTrue();
        assertThat(result.effectiveAction()).isEqualTo(ResumeAction.REPLY);
    }

    @Test
    void approvalWithTextGoesToClassification() {
        EntryResult result = guard.guard(context(CheckpointType.APPROVAL, "好的，继续"));

        assertThat(result.forceContinue()).isFalse();
        assertThat(result.failedReply()).isNull();
    }

    @Test
    void approvalWithoutTextIsRejected() {
        EntryResult result = guard.guard(context(CheckpointType.APPROVAL, null));

        assertThat(result.failedReply()).isNotNull();
    }

    private static TurnContext context(CheckpointType type, String userInput) {
        TurnRequest request = new TurnRequest();
        request.setChannel("web");
        request.setUserInput(userInput);

        TaskMetadata metadata = new TaskMetadata();
        metadata.setTaskId("task-1");
        metadata.setStatus(TaskStatus.SUSPENDED);

        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setType(type);
        checkpoint.setAllowedActions(type.defaultActions());

        TurnScope turn = mock(TurnScope.class);
        when(turn.conversation()).thenReturn(new ConversationFence("conv-1", null, "task-1", 1L, null, 0L));
        when(turn.task()).thenReturn(Optional.of(metadata));
        when(turn.checkpoint()).thenReturn(Optional.of(checkpoint));
        return new TurnContext(request, turn);
    }
}
