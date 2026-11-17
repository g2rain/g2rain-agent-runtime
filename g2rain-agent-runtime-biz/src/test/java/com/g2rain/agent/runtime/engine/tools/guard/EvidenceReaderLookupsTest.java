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
import com.g2rain.agent.runtime.spi.Lookup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EvidenceReader#nodeLookups} 验收。
 *
 * @author alpha
 * @since 2026/11/17
 */
class EvidenceReaderLookupsTest {

    @Test
    void nodeLookups_shouldIncludeEmptySuccessReadsAndUpstream() {
        RuntimeStateFacade stateFacade = mock(RuntimeStateFacade.class);
        EvidenceReader reader = new EvidenceReader(stateFacade);

        EvidenceHead localEmpty = head("read_local", SideEffect.NONE, EvidenceStatus.SUCCESS, null, "{}");
        EvidenceHead localWrite = head("save", SideEffect.WRITE, EvidenceStatus.SUCCESS, true, "{\"id\":1}");
        EvidenceHead localFail = head("read_fail", SideEffect.NONE, EvidenceStatus.FAILED, null, "{}");
        EvidenceHead localBizFail = head("read_biz", SideEffect.NONE, EvidenceStatus.SUCCESS, false, "{}");
        EvidenceHead upstream = head("read_up", SideEffect.NONE, EvidenceStatus.SUCCESS, true, "{\"q\":\"a\"}");

        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        NodeScope scope = mock(NodeScope.class);
        TaskGraph graph = mock(TaskGraph.class);
        GraphFrame frame = mock(GraphFrame.class);
        TurnScope turn = mock(TurnScope.class);
        when(ctx.getNodeId()).thenReturn("n1");
        when(ctx.scope()).thenReturn(scope);
        when(ctx.getGraph()).thenReturn(graph);
        when(ctx.getFrame()).thenReturn(frame);
        when(frame.turn()).thenReturn(turn);
        when(scope.evidence()).thenReturn(List.of(localEmpty, localWrite, localFail, localBizFail));
        when(graph.upstream("n1")).thenReturn(List.of("n0"));
        when(stateFacade.evidenceOf(turn, "n0")).thenReturn(List.of(upstream));

        List<Lookup> lookups = reader.nodeLookups(ctx);
        assertEquals(2, lookups.size());
        assertEquals("read_local", lookups.get(0).toolName());
        assertEquals("read_up", lookups.get(1).toolName());
        assertEquals("a", lookups.get(1).arguments().path("q").asString());
    }

    private static EvidenceHead head(String tool, SideEffect sideEffect, EvidenceStatus status,
                                     Boolean businessOk, String argsBrief) {
        return new EvidenceHead("e-" + tool, tool, sideEffect, status, businessOk, null, null, FailureKind.NONE,
            null, null, argsBrief, null, null, null, null, 1, "trace", null);
    }
}
