package com.g2rain.agent.runtime.domain.graph;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TaskGraph#doneWriteSteps()} 测试。
 *
 * @author alpha
 * @since 2026/11/16
 */
class TaskGraphDoneWriteStepsTest {

    private static final String TARGET = "cms_article_publish";

    @Test
    void doneWriteSteps_shouldListOnlyDoneNodesWithTarget() {
        TaskGraph graph = new TaskGraph();
        graph.getNodes().add(node("发布文章", NodeStatus.DONE, TARGET));
        graph.getNodes().add(node("查询文章", NodeStatus.DONE, null));
        graph.getNodes().add(node("发布标签", NodeStatus.READY, TARGET));

        assertEquals(List.of("发布文章"), graph.doneWriteSteps());
    }

    private static TaskNode node(String title, NodeStatus status, @Nullable String target) {
        TaskNode node = new TaskNode();
        node.setNodeId(title);
        node.setTitle(title);
        node.setStatus(status);
        node.setTarget(target);
        return node;
    }
}
