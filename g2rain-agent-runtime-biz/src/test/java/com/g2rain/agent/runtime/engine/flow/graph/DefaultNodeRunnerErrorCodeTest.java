package com.g2rain.agent.runtime.engine.flow.graph;

import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.react.ReActLoopResult;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.engine.flow.react.ReActLoop;
import com.g2rain.agent.runtime.engine.state.EvidenceFlow;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.ToolInvoker;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.event.StepEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultNodeRunner} 节点失败/取消错误码用例 N4～N7。
 *
 * @author alpha
 * @since 2026/11/22
 */
class DefaultNodeRunnerErrorCodeTest {

    private NodeStateMachine nodeStateMachine;

    private ReActLoop reActLoop;

    private RuntimeStateFacade runtimeStateFacade;

    private TurnScope turn;

    private DefaultNodeRunner runner;

    private TaskNode node;

    private GraphFrame frame;

    @BeforeEach
    void setUp() {
        nodeStateMachine = new DefaultNodeStateMachine();
        reActLoop = mock(ReActLoop.class);
        runtimeStateFacade = mock(RuntimeStateFacade.class);
        StepEvents stepEvents = mock(StepEvents.class);
        when(stepEvents.start(any(), any(), any(), any())).thenReturn("step-1");
        NodeToolset nodeToolset = mock(NodeToolset.class);
        when(nodeToolset.of(any(), any())).thenReturn(List.of());
        when(runtimeStateFacade.history(any(), anyList(), any())).thenReturn(List.of());
        when(runtimeStateFacade.open(any(), any(), any())).thenReturn(mock(NodeScope.class));
        runner = new DefaultNodeRunner(nodeStateMachine, nodeToolset, mock(WriteVerifier.class), reActLoop,
            mock(ToolInvoker.class), runtimeStateFacade, mock(EvidenceFlow.class), stepEvents);

        node = new TaskNode();
        node.setNodeId("n1");
        node.setSkillKey("skill");
        node.setStatus(NodeStatus.READY);
        TaskGraph graph = new TaskGraph();
        graph.setNodes(List.of(node));
        TaskMetadata meta = new TaskMetadata();
        meta.setTaskId("t1");
        meta.setCurrentNodeId("n1");
        turn = mock(TurnScope.class);
        when(turn.task()).thenReturn(Optional.of(meta));
        when(turn.graph()).thenReturn(graph);
        when(turn.checkpoint()).thenReturn(Optional.empty());
        when(turn.resumed()).thenReturn(Optional.empty());
        frame = new GraphFrame(turn, List.of(), null, null, null, null, null);
    }

    /** N4：轮次用尽 → FAILED + 42256。 */
    @Test
    void n4_roundsExhausted_writesNodeRoundsExhausted() {
        when(reActLoop.run(any())).thenReturn(new ReActLoopResult(NodeStatus.FAILED, "rounds", null, null,
            RuntimeErrorCode.NODE_ROUNDS_EXHAUSTED.code()));

        runner.run(node, frame);

        assertThat(node.getStatus()).isEqualTo(NodeStatus.FAILED);
        verify(turn).mark(eq("n1"), eq(NodeStatus.FAILED), eq(RuntimeErrorCode.NODE_ROUNDS_EXHAUSTED.code()),
            eq("rounds"));
    }

    /** N5：连续无进展 → 42257。 */
    @Test
    void n5_stalled_writesNodeStalled() {
        when(reActLoop.run(any())).thenReturn(new ReActLoopResult(NodeStatus.FAILED, "stalled", null, null,
            RuntimeErrorCode.NODE_STALLED.code()));

        runner.run(node, frame);

        verify(turn).mark(eq("n1"), eq(NodeStatus.FAILED), eq(RuntimeErrorCode.NODE_STALLED.code()), eq("stalled"));
    }

    /** N6：builtin_node_fail → 42258，文案为模型原因。 */
    @Test
    void n6_declaredFail_writesNodeDeclaredFailed() {
        when(reActLoop.run(any())).thenReturn(new ReActLoopResult(NodeStatus.FAILED, "下游超时", null, null,
            RuntimeErrorCode.NODE_DECLARED_FAILED.code()));

        runner.run(node, frame);

        verify(turn).mark(eq("n1"), eq(NodeStatus.FAILED), eq(RuntimeErrorCode.NODE_DECLARED_FAILED.code()),
            eq("下游超时"));
    }

    /** N7：取消 → errorCode 为空。 */
    @Test
    void n7_cancelled_errorCodeNull() {
        when(reActLoop.run(any())).thenReturn(new ReActLoopResult(NodeStatus.CANCELLED, "cancelled", null, null));

        runner.run(node, frame);

        verify(turn).mark(eq("n1"), eq(NodeStatus.CANCELLED), isNull(), eq("cancelled"));
    }
}
