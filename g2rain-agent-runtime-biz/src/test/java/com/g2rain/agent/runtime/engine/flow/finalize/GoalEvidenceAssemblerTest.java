package com.g2rain.agent.runtime.engine.flow.finalize;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.spec.SideEffect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GoalEvidenceAssembler} 用例 P6。
 *
 * @author alpha
 * @since 2026/11/22
 */
class GoalEvidenceAssemblerTest {

    /** P6：SUCCESS 且 businessOk=false → 不出现在 evidence。 */
    @Test
    void p6_calls_excludesBusinessFailure() {
        EvidenceHead failed = new EvidenceHead("e1", "save", SideEffect.WRITE, EvidenceStatus.SUCCESS,
            false, false, null, FailureKind.NONE, null, null, null, "业务失败摘要", null, null, null, 1, "t", null);
        TaskNode node = new TaskNode();
        node.setNodeId("n1");
        node.setStatus(NodeStatus.DONE);
        TaskGraph graph = new TaskGraph();
        graph.setNodes(List.of(node));
        TaskMetadata meta = new TaskMetadata();
        meta.setGoalSummary("goal");
        TurnScope turn = mock(TurnScope.class);
        when(turn.task()).thenReturn(Optional.of(meta));
        when(turn.graph()).thenReturn(graph);
        when(turn.dialogue(anyInt())).thenReturn(List.of());
        TurnContext ctx = new TurnContext(new TurnRequest(), turn);

        GoalEvidencePack pack = GoalEvidenceAssembler.assemble(ctx, new RuntimeProperties.GoalGate(),
            _ -> List.of(failed));

        assertThat(pack.nodes()).hasSize(1);
        assertThat(pack.nodes().getFirst().evidence()).isEmpty();
    }
}
