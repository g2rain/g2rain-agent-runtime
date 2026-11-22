package com.g2rain.agent.runtime.engine.flow.react;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ReactProjectionFactory} prior 块用例 P3。
 *
 * @author alpha
 * @since 2026/11/22
 */
class ReactProjectionFactoryPriorTest {

    /** P3：任务和实体都为空 → 不输出 prior 块。 */
    @Test
    void p3_prior_emptyTasksAndEntities_omitsBlock() {
        TurnScope turn = mock(TurnScope.class);
        when(turn.prior()).thenReturn(Optional.of(new Prior(List.of(), Map.of(), List.of())));
        when(turn.graph()).thenReturn(new TaskGraph());
        when(turn.shared()).thenReturn(Map.of());
        when(turn.sessionShared()).thenReturn(Map.of());
        NodeScope node = mock(NodeScope.class);
        when(node.turn()).thenReturn(turn);
        when(node.nodeId()).thenReturn("n1");
        when(node.inputs()).thenReturn(Map.of());
        when(node.outputs()).thenReturn(Map.of());
        when(node.state()).thenReturn(Map.of());
        when(node.evidence()).thenReturn(List.of());
        when(node.note(any())).thenReturn(null);

        PhaseHint phaseHint = mock(PhaseHint.class);
        when(phaseHint.hint(any(), any(), any(), anyBoolean())).thenReturn(null);
        ReactProjectionFactory factory = new ReactProjectionFactory(phaseHint, new RuntimeProperties(),
            mock(RuntimeStateFacade.class));
        PromptAssembleRequest request = new PromptAssembleRequest(List.of(), List.of(), node, "n1", List.of(),
            null, null, null, null, null, null, null, null, false, List.of());

        assertThat(factory.blocks(request).stream().map(ReactProjection.TaggedBlock::tag))
            .doesNotContain("prior");
    }
}
