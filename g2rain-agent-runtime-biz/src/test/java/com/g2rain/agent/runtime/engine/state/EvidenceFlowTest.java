package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.domain.tool.ToolCall;
import com.g2rain.agent.runtime.domain.tool.ToolResult;
import com.g2rain.agent.runtime.dto.ToolCallRecordDto;
import com.g2rain.agent.runtime.engine.state.repo.CallRepository;
import com.g2rain.agent.runtime.engine.state.repo.PayloadRepository;
import com.g2rain.agent.runtime.engine.store.StateStore;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.spec.SideEffect;
import com.g2rain.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EvidenceFlow}：任务壳预写（U10～U12）与大报文分流（V1～V4）。
 *
 * @author alpha
 * @since 2026/11/22
 */
class EvidenceFlowTest {

    private CallRepository calls;

    private PayloadRepository payloads;

    private TurnFlushPlan flushPlan;

    private RuntimeStatePersistence persistence;

    private StateStore store;

    private RuntimeProperties properties;

    private EvidenceFlow flow;

    @BeforeEach
    void setUp() {
        calls = mock(CallRepository.class);
        payloads = mock(PayloadRepository.class);
        flushPlan = mock(TurnFlushPlan.class);
        persistence = mock(RuntimeStatePersistence.class);
        store = mock(StateStore.class);
        properties = new RuntimeProperties();
        when(store.hGet(any(), any())).thenReturn(null);
        when(calls.findLiveWrite(any(), any(), any())).thenReturn(Optional.empty());
        doNothing().when(flushPlan).apply(any());
        flow = new EvidenceFlow(calls, payloads, flushPlan, properties, store, persistence);
    }

    /** U10：正常路径，persistShell 在 insertStarted 之前。 */
    @Test
    void u10_prewrite_persistShellBeforeInsertStarted() {
        DefaultNodeScope node = node();

        PrewriteResult result = flow.prewrite(node, writeCall());

        assertThat(result).isInstanceOf(PrewriteResult.Proceed.class);
        InOrder order = inOrder(persistence, calls);
        order.verify(persistence).persistShell(node.turn());
        order.verify(calls).insertStarted(any());
    }

    /** U11：persistShell 瞬时失败 → Blocked / UNKNOWN_PRE_CALL，不 insertStarted。 */
    @Test
    void u11_prewrite_transientShellFailure_blocksWithoutInsert() {
        DefaultNodeScope node = node();
        doThrow(new QueryTimeoutException("timeout")).when(persistence).persistShell(any());

        PrewriteResult result = flow.prewrite(node, writeCall());

        assertThat(result).isInstanceOf(PrewriteResult.Blocked.class);
        assertThat(((PrewriteResult.Blocked) result).status()).isEqualTo(EvidenceStatus.UNKNOWN_PRE_CALL);
        verify(calls, never()).insertStarted(any());
    }

    /** U12：persistShell 永久失败 → CANCELLED + STATE_WRITE_FAILED，不 insertStarted。 */
    @Test
    void u12_prewrite_permanentShellFailure_cancelsAndThrows() {
        DefaultNodeScope node = node();
        doThrow(new DataIntegrityViolationException("dup")).when(persistence).persistShell(any());

        assertThatThrownBy(() -> flow.prewrite(node, writeCall()))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(RuntimeErrorCode.STATE_WRITE_FAILED.code());
        verify(calls, never()).insertStarted(any());
        assertThat(node.evidence())
            .extracting(EvidenceHead::status)
            .contains(EvidenceStatus.CANCELLED);
    }

    /** V1：record 超限 → 不进 turn.payloads，payloadId 落库，saveArtifact。 */
    @Test
    void v1_record_oversize_skipsRedisPutsArtifact() {
        properties.getState().setPayloadInlineMaxBytes(16);
        DefaultNodeScope node = node();
        ToolCall call = new ToolCall("e-big", "tool.read", JsonNodeFactory.instance.objectNode(), null,
            SideEffect.NONE);
        ToolResult result = success(bigPayload());

        String evidenceId = flow.record(node, call, result);

        assertThat(node.turn().payloads()).doesNotContainKey(evidenceId);
        ArgumentCaptor<ToolCallRecordDto> captor = ArgumentCaptor.forClass(ToolCallRecordDto.class);
        verify(calls).insertStarted(captor.capture());
        assertThat(captor.getValue().getPayloadId()).isEqualTo(evidenceId);
        assertThat(captor.getValue().getResponse()).isNull();
        verify(payloads).saveArtifact(eq(evidenceId), eq("t1"), eq("n1"), eq("tool.read"), eq("trace"), anyString());
    }

    /** V2：settle 超限 → 不进 turn.payloads，update 带 payloadId，saveArtifact。 */
    @Test
    void v2_settle_oversize_skipsRedisPutsArtifact() {
        properties.getState().setPayloadInlineMaxBytes(16);
        DefaultNodeScope node = node();
        node.addEvidence(started("e-settle"));
        ToolResult result = success(bigPayload());

        flow.settle(node, "e-settle", result);

        assertThat(node.turn().payloads()).doesNotContainKey("e-settle");
        ArgumentCaptor<EvidenceHead> headCaptor = ArgumentCaptor.forClass(EvidenceHead.class);
        verify(calls).update(headCaptor.capture(), isNull(), any());
        assertThat(headCaptor.getValue().payloadId()).isEqualTo("e-settle");
        assertThat(headCaptor.getValue().status()).isEqualTo(EvidenceStatus.SUCCESS);
        verify(payloads).saveArtifact(eq("e-settle"), eq("t1"), eq("n1"), eq("tool.write"), eq("trace"), anyString());
    }

    /** V3：record 未超限 → 进 turn.payloads，response 有报文，不 saveArtifact。 */
    @Test
    void v3_record_inline_putsPayloadNoArtifact() {
        DefaultNodeScope node = node();
        ToolCall call = new ToolCall("e-small", "tool.read", JsonNodeFactory.instance.objectNode(), null,
            SideEffect.NONE);
        JsonNode payload = JsonNodeFactory.instance.objectNode().put("ok", true);
        ToolResult result = success(payload);

        String evidenceId = flow.record(node, call, result);

        assertThat(node.turn().payloads()).containsKey(evidenceId);
        ArgumentCaptor<ToolCallRecordDto> captor = ArgumentCaptor.forClass(ToolCallRecordDto.class);
        verify(calls).insertStarted(captor.capture());
        assertThat(captor.getValue().getPayloadId()).isNull();
        assertThat(captor.getValue().getResponse()).isNotBlank();
        verify(payloads, never()).saveArtifact(any(), any(), any(), any(), any(), any());
    }

    /** V4：settle 时 saveArtifact 失败 → 异常上抛；证据头已是终态。 */
    @Test
    void v4_settle_artifactFailure_propagatesAfterSettledHead() {
        properties.getState().setPayloadInlineMaxBytes(16);
        DefaultNodeScope node = node();
        node.addEvidence(started("e-fail"));
        doThrow(new IllegalStateException("artifact down")).when(payloads)
            .saveArtifact(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> flow.settle(node, "e-fail", success(bigPayload())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("artifact down");
        assertThat(node.evidence())
            .extracting(EvidenceHead::status)
            .doesNotContain(EvidenceStatus.STARTED)
            .contains(EvidenceStatus.SUCCESS);
    }

    private static DefaultNodeScope node() {
        TaskMetadata meta = new TaskMetadata();
        meta.setTaskId("t1");
        meta.setConversationId("c1");
        meta.setStatus(TaskStatus.RUNNING);
        TaskNode graphNode = new TaskNode();
        graphNode.setNodeId("n1");
        graphNode.setStatus(NodeStatus.RUNNING);
        TaskGraph graph = new TaskGraph();
        graph.setNodes(List.of(graphNode));
        ConversationFence fence = new ConversationFence("c1", "u1", "t1", 1L, "trace", System.currentTimeMillis());
        DefaultTurnScope turn = new DefaultTurnScope("1", "u1", "trace", fence, List.of(), List.of(), new HashMap<>(),
            meta, graph, null, new HashMap<>(), null, new HashMap<>(), 60);
        DefaultNodeScope node = new DefaultNodeScope(turn, "n1", Map.of(), Map.of(), Map.of(), List.of(), List.of(), 1,
            0);
        turn.attach(node);
        return node;
    }

    private static ToolCall writeCall() {
        return new ToolCall("call-1", "tool.write", JsonNodeFactory.instance.objectNode(), "idem-1", SideEffect.WRITE);
    }

    private static EvidenceHead started(String evidenceId) {
        return new EvidenceHead(evidenceId, "tool.write", SideEffect.WRITE, EvidenceStatus.STARTED, null, null, null,
            FailureKind.NONE, "idem-1", "hash", null, null, null, null, null, 1, "trace", null);
    }

    private static ToolResult success(JsonNode payload) {
        return new ToolResult(EvidenceStatus.SUCCESS, payload, true, false, Map.of(), FailureKind.NONE, null, "ok");
    }

    private static JsonNode bigPayload() {
        return JsonNodeFactory.instance.objectNode().put("blob", "x".repeat(64));
    }
}
