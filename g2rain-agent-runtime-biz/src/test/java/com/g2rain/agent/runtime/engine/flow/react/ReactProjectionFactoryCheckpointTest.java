package com.g2rain.agent.runtime.engine.flow.react;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.HaltReason;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
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
 * {@link ReactProjectionFactory} 已删除 checkpoint 块用例（PR-7）。
 *
 * @author alpha
 * @since 2026/11/22
 */
class ReactProjectionFactoryCheckpointTest {

    /** 已消费断点（resumed 非空）时，投影块标签不含 checkpoint。 */
    @Test
    void resumedCheckpoint_omitsCheckpointBlock() {
        TaskCheckpoint resumed = new TaskCheckpoint();
        resumed.setType(CheckpointType.FOLLOW_UP);
        resumed.setHaltReason(HaltReason.MISSING_INPUT);
        resumed.setNodeId("n1");
        resumed.setQuestion("请补充标题");

        TurnScope turn = mock(TurnScope.class);
        when(turn.prior()).thenReturn(Optional.empty());
        when(turn.graph()).thenReturn(new TaskGraph());
        when(turn.shared()).thenReturn(Map.of());
        when(turn.sessionShared()).thenReturn(Map.of());
        when(turn.checkpoint()).thenReturn(Optional.empty());
        when(turn.resumed()).thenReturn(Optional.of(resumed));
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
            .doesNotContain("checkpoint");
    }
}
