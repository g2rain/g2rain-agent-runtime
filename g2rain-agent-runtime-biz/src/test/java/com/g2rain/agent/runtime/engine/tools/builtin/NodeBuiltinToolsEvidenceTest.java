package com.g2rain.agent.runtime.engine.tools.builtin;

import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.state.OutputContractValidator;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.ToolExecutionContexts;
import com.g2rain.agent.runtime.engine.tools.ToolRejectedException;
import com.g2rain.agent.runtime.engine.tools.guard.EvidenceReader;
import com.g2rain.agent.runtime.engine.tools.guard.SourceVerifier;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.spec.SideEffect;
import com.g2rain.agent.runtime.support.PlatformCatalog;
import com.g2rain.common.json.JsonCodecFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NodeBuiltinTools#evidenceGet} 范围用例 E1～E5。
 *
 * @author alpha
 * @since 2026/11/22
 */
class NodeBuiltinToolsEvidenceTest {

    private RuntimeStateFacade stateFacade;

    private EvidenceReader evidenceReader;

    private NodeBuiltinTools tools;

    private ToolContext toolContext;

    private ToolExecutionContext ctx;

    private NodeScope scope;

    private TurnScope turn;

    private TaskGraph graph;

    @BeforeEach
    void setUp() {
        stateFacade = mock(RuntimeStateFacade.class);
        evidenceReader = new EvidenceReader(stateFacade);
        tools = new NodeBuiltinTools(mock(OutputContractValidator.class), stateFacade, evidenceReader,
            mock(SourceVerifier.class), mock(PlatformCatalog.class));
        ctx = mock(ToolExecutionContext.class);
        scope = mock(NodeScope.class);
        turn = mock(TurnScope.class);
        graph = mock(TaskGraph.class);
        GraphFrame frame = mock(GraphFrame.class);
        when(ctx.getNodeId()).thenReturn("n1");
        when(ctx.getTaskId()).thenReturn("t1");
        when(ctx.scope()).thenReturn(scope);
        when(ctx.getGraph()).thenReturn(graph);
        when(ctx.getFrame()).thenReturn(frame);
        when(frame.turn()).thenReturn(turn);
        when(graph.upstream("n1")).thenReturn(List.of("n0"));
        toolContext = ToolExecutionContexts.toSpring(ctx);
    }

    /** E1：本节点证据 → 返回报文。 */
    @Test
    void e1_evidenceGet_local_returnsPayload() {
        when(scope.evidence()).thenReturn(List.of(head("e-local", EvidenceStatus.SUCCESS)));
        JsonNode payload = JsonCodecFactory.instance().str2node("{\"ok\":true}");
        when(stateFacade.payload(turn, "t1", "e-local")).thenReturn(payload);

        assertThat(tools.evidenceGet("e-local", toolContext)).isEqualTo(payload);
        verify(stateFacade).payload(turn, "t1", "e-local");
    }

    /** E2：上游节点证据 → 返回报文。 */
    @Test
    void e2_evidenceGet_upstream_returnsPayload() {
        when(scope.evidence()).thenReturn(List.of());
        when(stateFacade.evidenceOf(turn, "n0")).thenReturn(List.of(head("e-up", EvidenceStatus.SUCCESS)));
        JsonNode payload = JsonCodecFactory.instance().str2node("{\"from\":\"up\"}");
        when(stateFacade.payload(turn, "t1", "e-up")).thenReturn(payload);

        assertThat(tools.evidenceGet("e-up", toolContext)).isEqualTo(payload);
    }

    /** E3：兄弟节点证据 → 拒绝，不读 payload。 */
    @Test
    void e3_evidenceGet_sibling_rejectsWithoutPayload() {
        when(scope.evidence()).thenReturn(List.of());
        when(stateFacade.evidenceOf(turn, "n0")).thenReturn(List.of());

        assertThatThrownBy(() -> tools.evidenceGet("e-sibling", toolContext))
            .isInstanceOf(ToolRejectedException.class)
            .extracting(ex -> ((ToolRejectedException) ex).error())
            .isEqualTo(RuntimeErrorCode.EVIDENCE_PATH_INVALID);
        verify(stateFacade, never()).payload(any(), any(), any());
    }

    /** E4：不属于本任务的 id → 同 E3。 */
    @Test
    void e4_evidenceGet_foreignId_rejectsWithoutPayload() {
        when(scope.evidence()).thenReturn(List.of(head("e-local", EvidenceStatus.SUCCESS)));
        when(stateFacade.evidenceOf(turn, "n0")).thenReturn(List.of());

        assertThatThrownBy(() -> tools.evidenceGet("e-other-task", toolContext))
            .isInstanceOf(ToolRejectedException.class)
            .extracting(ex -> ((ToolRejectedException) ex).error())
            .isEqualTo(RuntimeErrorCode.EVIDENCE_PATH_INVALID);
        verify(stateFacade, never()).payload(any(), any(), eq("e-other-task"));
    }

    /** E5：本节点失败状态证据 → 仍可读。 */
    @Test
    void e5_evidenceGet_localFailed_returnsPayload() {
        when(scope.evidence()).thenReturn(List.of(head("e-fail", EvidenceStatus.FAILED)));
        JsonNode payload = JsonCodecFactory.instance().str2node("{\"err\":1}");
        when(stateFacade.payload(turn, "t1", "e-fail")).thenReturn(payload);

        assertThat(tools.evidenceGet("e-fail", toolContext)).isEqualTo(payload);
    }

    private static EvidenceHead head(String evidenceId, EvidenceStatus status) {
        return new EvidenceHead(evidenceId, "tool", SideEffect.NONE, status, null, null, null, FailureKind.NONE,
            null, null, null, null, null, null, null, 1, "trace", null);
    }
}
