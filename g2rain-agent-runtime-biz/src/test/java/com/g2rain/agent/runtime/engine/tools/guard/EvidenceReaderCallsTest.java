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
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EvidenceReader#nodeCalls} 验收（含 E8～E10）。
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
        EvidenceHead localEmpty = head("e-read_local", "read_local", SideEffect.NONE, EvidenceStatus.SUCCESS, null);
        EvidenceHead localWrite = head("e-save", "save", SideEffect.WRITE, EvidenceStatus.SUCCESS, true);
        EvidenceHead localFail = head("e-read_fail", "read_fail", SideEffect.NONE, EvidenceStatus.FAILED, null);
        EvidenceHead localBizFail = head("e-read_biz", "read_biz", SideEffect.NONE, EvidenceStatus.SUCCESS, false);
        EvidenceHead upstream = head("e-read_up", "read_up", SideEffect.NONE, EvidenceStatus.SUCCESS, true);
        when(scope.evidence()).thenReturn(List.of(localEmpty, localWrite, localFail, localBizFail));
        when(stateFacade.evidenceOf(turn, "n0")).thenReturn(List.of(upstream));
        ObjectNode saveArgs = JsonNodeFactory.instance.objectNode().put("id", 1);
        ObjectNode upArgs = JsonNodeFactory.instance.objectNode().put("q", "a");
        when(stateFacade.argsOf(eq(turn), any()))
            .thenReturn(Map.of(
                "e-read_local", JsonNodeFactory.instance.objectNode(),
                "e-save", saveArgs,
                "e-read_up", upArgs));

        List<Call> calls = reader.nodeCalls(ctx);

        assertEquals(List.of("save", "read_local", "read_up"), calls.stream().map(Call::toolName).toList());
        assertEquals(SideEffect.WRITE, calls.getFirst().sideEffect());
        assertEquals(1, calls.getFirst().arguments().path("id").asInt());
        assertEquals("a", calls.get(2).arguments().path("q").asString());
        assertTrue(calls.get(2).facts().isEmpty());
    }

    @Test
    void nodeCalls_shouldCarryExtractsOfEachCall() {
        EvidenceHead read = head("e-read_local", "read_local", SideEffect.NONE, EvidenceStatus.SUCCESS, true);
        when(scope.evidence()).thenReturn(List.of(read));
        when(stateFacade.evidenceOf(turn, "n0")).thenReturn(List.of());
        when(stateFacade.argsOf(eq(turn), any()))
            .thenReturn(Map.of("e-read_local", JsonNodeFactory.instance.objectNode()));
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

    /** E8：完整入参未脱敏、未截短。 */
    @Test
    void e8_nodeCalls_argumentsMatchFullRequest() {
        EvidenceHead local = head("e-full", "lookup", SideEffect.NONE, EvidenceStatus.SUCCESS, true);
        when(scope.evidence()).thenReturn(List.of(local));
        when(stateFacade.evidenceOf(turn, "n0")).thenReturn(List.of());
        String longText = "x".repeat(500);
        ObjectNode request = JsonNodeFactory.instance.objectNode()
            .put("password", "secret-plain")
            .put("note", longText);
        when(stateFacade.argsOf(eq(turn), any()))
            .thenReturn(Map.of("e-full", request));

        List<Call> calls = reader.nodeCalls(ctx);

        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().arguments()).isEqualTo(request);
        assertThat(calls.getFirst().arguments().path("password").asString()).isEqualTo("secret-plain");
        assertThat(calls.getFirst().arguments().path("note").asString()).hasSize(500);
    }

    /** E9：查不到 request → 该条空对象，其余正常。 */
    @Test
    void e9_nodeCalls_missingRequest_usesEmptyObject() {
        EvidenceHead first = head("e-a", "a", SideEffect.NONE, EvidenceStatus.SUCCESS, true);
        EvidenceHead second = head("e-b", "b", SideEffect.NONE, EvidenceStatus.SUCCESS, true);
        when(scope.evidence()).thenReturn(List.of(first, second));
        when(stateFacade.evidenceOf(turn, "n0")).thenReturn(List.of());
        ObjectNode aArgs = JsonNodeFactory.instance.objectNode().put("k", "v");
        when(stateFacade.argsOf(eq(turn), any()))
            .thenReturn(Map.of("e-a", aArgs));

        List<Call> calls = reader.nodeCalls(ctx);

        // 同节点新的在前：e-b 缺失 → 空对象；e-a 有完整参数
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).toolName()).isEqualTo("b");
        assertThat(calls.get(0).arguments()).isEqualTo(JsonNodeFactory.instance.objectNode());
        assertThat(calls.get(1).toolName()).isEqualTo("a");
        assertThat(calls.get(1).arguments()).isEqualTo(aArgs);
    }

    /** E10：多条成功证据时 argsOf 只调一次，且含全部 id。 */
    @Test
    void e10_nodeCalls_argsOfCalledOnceWithAllIds() {
        EvidenceHead local1 = head("e-1", "t1", SideEffect.NONE, EvidenceStatus.SUCCESS, true);
        EvidenceHead local2 = head("e-2", "t2", SideEffect.WRITE, EvidenceStatus.SUCCESS, true);
        EvidenceHead upstream = head("e-3", "t3", SideEffect.NONE, EvidenceStatus.SUCCESS, true);
        when(scope.evidence()).thenReturn(List.of(local1, local2));
        when(stateFacade.evidenceOf(turn, "n0")).thenReturn(List.of(upstream));
        Map<String, JsonNode> args = new LinkedHashMap<>();
        args.put("e-1", JsonNodeFactory.instance.objectNode().put("n", 1));
        args.put("e-2", JsonNodeFactory.instance.objectNode().put("n", 2));
        args.put("e-3", JsonNodeFactory.instance.objectNode().put("n", 3));
        when(stateFacade.argsOf(eq(turn), any())).thenReturn(args);

        List<Call> calls = reader.nodeCalls(ctx);

        assertThat(calls).hasSize(3);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(stateFacade, times(1)).argsOf(eq(turn), ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder("e-1", "e-2", "e-3");
    }

    private static EvidenceHead head(String evidenceId, String tool, SideEffect sideEffect, EvidenceStatus status,
                                     Boolean businessOk) {
        return new EvidenceHead(evidenceId, tool, sideEffect, status, businessOk, null, null, FailureKind.NONE,
            null, null, null, null, null, null, null, 1, "trace", null);
    }
}
