package com.g2rain.agent.runtime.engine.tools.guard;

import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.spec.SideEffect;
import com.g2rain.common.json.JsonCodecFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EvidenceReader#matches} 范围用例 E6、E7。
 *
 * @author alpha
 * @since 2026/11/22
 */
class EvidenceReaderMatchesTest {

    private RuntimeStateFacade stateFacade;

    private EvidenceReader reader;

    private ToolExecutionContext ctx;

    private NodeScope scope;

    private TurnScope turn;

    @BeforeEach
    void setUp() {
        stateFacade = mock(RuntimeStateFacade.class);
        reader = new EvidenceReader(stateFacade);
        ctx = mock(ToolExecutionContext.class);
        scope = mock(NodeScope.class);
        turn = mock(TurnScope.class);
        TaskGraph graph = mock(TaskGraph.class);
        GraphFrame frame = mock(GraphFrame.class);
        when(ctx.getNodeId()).thenReturn("n1");
        when(ctx.getTaskId()).thenReturn("t1");
        when(ctx.scope()).thenReturn(scope);
        when(ctx.getGraph()).thenReturn(graph);
        when(ctx.getFrame()).thenReturn(frame);
        when(frame.turn()).thenReturn(turn);
        when(graph.upstream("n1")).thenReturn(List.of("n0"));
        when(stateFacade.evidenceOf(turn, "n0")).thenReturn(List.of());
    }

    /** E6：不可达 evidenceId → false，不读 payload。 */
    @Test
    void e6_matches_unreachable_returnsFalseWithoutPayload() {
        when(scope.evidence()).thenReturn(List.of(head("e-local", EvidenceStatus.SUCCESS, true)));
        JsonNode expected = JsonCodecFactory.instance().str2node("{\"v\":1}");

        assertThat(reader.matches(ctx, "e-foreign", null, expected)).isFalse();
        verify(stateFacade, never()).payload(any(), any(), any());
    }

    /** E7：本节点失败状态证据 → false，不读 payload。 */
    @Test
    void e7_matches_localFailed_returnsFalseWithoutPayload() {
        when(scope.evidence()).thenReturn(List.of(head("e-fail", EvidenceStatus.FAILED, null)));
        JsonNode expected = JsonCodecFactory.instance().str2node("{\"err\":1}");

        assertThat(reader.matches(ctx, "e-fail", null, expected)).isFalse();
        verify(stateFacade, never()).payload(any(), any(), any());
    }

    private static EvidenceHead head(String evidenceId, EvidenceStatus status, Boolean businessOk) {
        return new EvidenceHead(evidenceId, "tool", SideEffect.NONE, status, businessOk, null, null, FailureKind.NONE,
            null, null, null, null, null, null, null, 1, "trace", null);
    }
}
