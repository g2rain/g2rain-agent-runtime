package com.g2rain.agent.runtime.domain.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TaskGraph#resumeAwaiting} 用例 N11、N13。
 *
 * @author alpha
 * @since 2026/11/22
 */
class TaskGraphResumeAwaitingTest {

    /** N11：委派边指向缺失节点 → 返回父 id，状态不改。 */
    @Test
    void n11_resumeAwaiting_missingChild_returnsParentUnchanged() {
        TaskGraph graph = new TaskGraph();
        TaskNode parent = node("parent", NodeStatus.WAITING_DELEGATE);
        graph.setNodes(List.of(parent));
        graph.setEdges(List.of(new TaskEdge("missing", "parent", EdgeType.DELEGATE)));

        List<String> broken = graph.resumeAwaiting();

        assertThat(broken).containsExactly("parent");
        assertThat(parent.getStatus()).isEqualTo(NodeStatus.WAITING_DELEGATE);
        assertThat(parent.getErrorCode()).isNull();
        assertThat(parent.getFinishedAt()).isNull();
    }

    /** N13：子节点都已终态 → 父回到 READY，返回空列表。 */
    @Test
    void n13_resumeAwaiting_childrenTerminal_readyAndEmpty() {
        TaskGraph graph = new TaskGraph();
        TaskNode parent = node("parent", NodeStatus.WAITING_DELEGATE);
        TaskNode child = node("child", NodeStatus.DONE);
        graph.setNodes(List.of(parent, child));
        graph.setEdges(List.of(new TaskEdge("child", "parent", EdgeType.DELEGATE)));

        List<String> broken = graph.resumeAwaiting();

        assertThat(broken).isEmpty();
        assertThat(parent.getStatus()).isEqualTo(NodeStatus.READY);
    }

    private static TaskNode node(String id, NodeStatus status) {
        TaskNode node = new TaskNode();
        node.setNodeId(id);
        node.setStatus(status);
        return node;
    }
}
