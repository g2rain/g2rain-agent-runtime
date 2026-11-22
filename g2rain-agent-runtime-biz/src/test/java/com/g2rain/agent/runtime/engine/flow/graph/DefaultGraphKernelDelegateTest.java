package com.g2rain.agent.runtime.engine.flow.graph;

import com.g2rain.agent.runtime.domain.graph.EdgeType;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskEdge;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultGraphKernel} 委派链损坏用例 N12。
 *
 * @author alpha
 * @since 2026/11/22
 */
class DefaultGraphKernelDelegateTest {

    /** N12：损坏父节点经 turn.mark 失败，错误码与 finishedAt。 */
    @Test
    void n12_kernel_marksBrokenDelegateParent() {
        TaskNode parent = new TaskNode();
        parent.setNodeId("parent");
        parent.setStatus(NodeStatus.WAITING_DELEGATE);
        TaskGraph graph = new TaskGraph();
        graph.setNodes(List.of(parent));
        graph.setEdges(List.of(new TaskEdge("missing", "parent", EdgeType.DELEGATE)));

        TaskMetadata meta = new TaskMetadata();
        meta.setTaskId("t1");
        TurnScope turn = mock(TurnScope.class);
        when(turn.task()).thenReturn(Optional.of(meta));
        when(turn.graph()).thenReturn(graph);
        when(turn.checkpoint()).thenReturn(Optional.empty());
        // mark 走真实图节点，便于断言 finishedAt
        org.mockito.Mockito.doAnswer(inv -> {
            String id = inv.getArgument(0);
            NodeStatus status = inv.getArgument(1);
            String code = inv.getArgument(2);
            String message = inv.getArgument(3);
            TaskNode node = graph.find(id);
            if (java.util.Objects.nonNull(node)) {
                node.setStatus(status);
                node.setErrorCode(code);
                node.setErrorMessage(message);
                node.setFinishedAt(System.currentTimeMillis());
            }
            return null;
        }).when(turn).mark(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.nullable(String.class), org.mockito.ArgumentMatchers.nullable(String.class));

        GraphFrame frame = new GraphFrame(turn, List.of(), null, null, null, null, null);
        frame.markWakeDone();
        new DefaultGraphKernel().next(frame);

        verify(turn).mark(eq("parent"), eq(NodeStatus.FAILED), eq(RuntimeErrorCode.DELEGATE_BROKEN.code()),
            eq(RuntimeMessage.NODE_DELEGATE_BROKEN.text()));
        assertThat(parent.getStatus()).isEqualTo(NodeStatus.FAILED);
        assertThat(parent.getErrorCode()).isEqualTo(RuntimeErrorCode.DELEGATE_BROKEN.code());
        assertThat(parent.getFinishedAt()).isNotNull();
    }
}
