package com.g2rain.agent.runtime.engine.tools.guard;

import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.spec.ExtractSpec;
import com.g2rain.agent.runtime.spec.SideEffect;
import com.g2rain.agent.runtime.spec.ToolSpec;
import com.g2rain.agent.runtime.spi.Call;
import com.g2rain.agent.runtime.spi.Fact;
import com.g2rain.common.json.JsonCodecFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EvidenceReader#nodeCalls} 验收。
 *
 * @author alpha
 * @since 2026/11/20
 */
class EvidenceReaderCallsTest {

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
    }

    @Test
    void nodeCalls_shouldIncludeSuccessfulReadsAndWritesNewestFirst() {
        EvidenceHead localEmpty = head("read_local", SideEffect.NONE, EvidenceStatus.SUCCESS, null, "{}");
        EvidenceHead localWrite = head("save", SideEffect.WRITE, EvidenceStatus.SUCCESS, true, "{\"id\":1}");
        EvidenceHead localFail = head("read_fail", SideEffect.NONE, EvidenceStatus.FAILED, null, "{}");
        EvidenceHead localBizFail = head("read_biz", SideEffect.NONE, EvidenceStatus.SUCCESS, false, "{}");
        EvidenceHead upstream = head("read_up", SideEffect.NONE, EvidenceStatus.SUCCESS, true, "{\"q\":\"a\"}");
        when(scope.evidence()).thenReturn(List.of(localEmpty, localWrite, localFail, localBizFail));
        when(stateFacade.evidenceOf(turn, "n0")).thenReturn(List.of(upstream));

        List<Call> calls = reader.nodeCalls(ctx);

        assertEquals(List.of("save", "read_local", "read_up"), calls.stream().map(Call::toolName).toList());
        assertEquals(SideEffect.WRITE, calls.getFirst().sideEffect());
        assertEquals(1, calls.getFirst().arguments().path("id").asInt());
        assertEquals("a", calls.get(2).arguments().path("q").asString());
        assertTrue(calls.get(2).facts().isEmpty());
    }

    @Test
    void nodeCalls_shouldCarryExtractsOfEachCall() {
        EvidenceHead read = head("read_local", SideEffect.NONE, EvidenceStatus.SUCCESS, true, "{}");
        when(scope.evidence()).thenReturn(List.of(read));
        when(stateFacade.evidenceOf(turn, "n0")).thenReturn(List.of());
        ExtractSpec extract = new ExtractSpec();
        extract.setKey("item");
        extract.setPath("data");
        ToolSpec tool = new ToolSpec();
        tool.setExtracts(List.of(extract));
        SkillContract contract = mock(SkillContract.class);
        when(contract.tool("read_local")).thenReturn(tool);
        when(ctx.contract()).thenReturn(contract);
        when(stateFacade.payload(turn, "t1", "e-read_local"))
            .thenReturn(JsonCodecFactory.instance().str2node("{\"data\":{\"id\":7}}"));

        List<Call> calls = reader.nodeCalls(ctx);

        assertEquals(List.of(new Fact("item", JsonCodecFactory.instance().str2node("{\"id\":7}"))),
            calls.getFirst().facts());
    }

    private static EvidenceHead head(String tool, SideEffect sideEffect, EvidenceStatus status,
                                     Boolean businessOk, String argsBrief) {
        return new EvidenceHead("e-" + tool, tool, sideEffect, status, businessOk, null, null, FailureKind.NONE,
            null, null, argsBrief, null, null, null, null, 1, "trace", null);
    }
}
