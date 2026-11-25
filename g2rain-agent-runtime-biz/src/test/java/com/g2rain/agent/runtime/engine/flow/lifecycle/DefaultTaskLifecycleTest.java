package com.g2rain.agent.runtime.engine.flow.lifecycle;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;
import com.g2rain.agent.runtime.engine.TurnRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开任务时锁定技能修订。
 *
 * @author alpha
 * @since 2026/11/25
 */
class DefaultTaskLifecycleTest {

    @Test
    void openTask_locksSkillRevisionIdFromRequest() {
        RuntimeProperties properties = mock(RuntimeProperties.class);
        RuntimeProperties.State state = new RuntimeProperties.State();
        when(properties.getState()).thenReturn(state);

        TurnRequest request = new TurnRequest();
        request.setChannel("web");
        request.setUserInput("查文章");
        request.setSkillRevisionId(9001L);

        TurnScope turn = mock(TurnScope.class);
        when(turn.conversation()).thenReturn(new ConversationFence("conv-1", null, null, 1L, null, 0L));
        when(turn.task()).thenReturn(Optional.empty());
        when(turn.traceId()).thenReturn("trace-1");
        when(turn.allDialogue()).thenReturn(List.of());
        when(turn.records()).thenReturn(List.of());
        when(turn.entities()).thenReturn(Map.of());
        when(turn.start(any(TaskMetadata.class), any(Prior.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentSelection agent = new AgentSelection();
        agent.setAgentId(9L);
        TurnContext ctx = new TurnContext(request, turn);
        ctx.setDecision(TurnDecision.startFresh().withAgent(agent));

        new DefaultTaskLifecycle(properties).prepare(ctx);

        ArgumentCaptor<TaskMetadata> captor = ArgumentCaptor.forClass(TaskMetadata.class);
        verify(turn).start(captor.capture(), any(Prior.class));
        assertThat(captor.getValue().getSkillRevisionId()).isEqualTo(9001L);
        assertThat(captor.getValue().getAgentId()).isEqualTo(9L);
    }
}
