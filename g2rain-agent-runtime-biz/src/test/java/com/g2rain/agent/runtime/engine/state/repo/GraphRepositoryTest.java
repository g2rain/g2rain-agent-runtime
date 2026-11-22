package com.g2rain.agent.runtime.engine.state.repo;

import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskEdge;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.task.NodeSpace;
import com.g2rain.agent.runtime.dto.TaskGraphEdgeDto;
import com.g2rain.agent.runtime.dto.TaskGraphNodeDto;
import com.g2rain.agent.runtime.service.TaskGraphEdgeService;
import com.g2rain.agent.runtime.service.TaskGraphNodeService;
import com.g2rain.agent.runtime.vo.TaskGraphEdgeVo;
import com.g2rain.agent.runtime.vo.TaskGraphNodeVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GraphRepository} 图写入与 insertMissing 测试。
 *
 * @author alpha
 * @since 2026/11/22
 */
class GraphRepositoryTest {

    private static final String TASK_ID = "t1";

    private TaskGraphNodeService nodeService;

    private TaskGraphEdgeService edgeService;

    private GraphRepository repository;

    @BeforeEach
    void setUp() {
        nodeService = mock(TaskGraphNodeService.class);
        edgeService = mock(TaskGraphEdgeService.class);
        repository = new GraphRepository(nodeService, edgeService);
    }

    /** 固定：已有节点走更新写入。 */
    @Test
    void save_existingNode_updatesRow() {
        TaskGraphNodeVo old = nodeVo("A", 1L);
        when(nodeService.selectList(any())).thenReturn(List.of(old));
        when(edgeService.selectList(any())).thenReturn(List.of());
        TaskGraph graph = graph(node("A", NodeStatus.DONE));

        repository.save(TASK_ID, graph, Map.of());

        ArgumentCaptor<TaskGraphNodeDto> captor = ArgumentCaptor.forClass(TaskGraphNodeDto.class);
        verify(nodeService).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(1L);
        assertThat(captor.getValue().getStatus()).isEqualTo(NodeStatus.DONE.name());
    }

    /** U4：已有 A，图含 A/B 且 B 已打开 → 只插 B（带 state），插缺失边。 */
    @Test
    void u4_insertMissing_onlyNewNodeWithSpaceAndEdges() {
        when(nodeService.selectList(any())).thenReturn(List.of(nodeVo("A", 1L)));
        when(edgeService.selectList(any())).thenReturn(List.of());
        TaskNode a = node("A", NodeStatus.DONE);
        TaskNode b = node("B", NodeStatus.READY);
        b.getDependsOn().add("A");
        TaskGraph graph = new TaskGraph();
        graph.setNodes(List.of(a, b));
        graph.setEdges(List.of(new TaskEdge("A", "B")));
        NodeSpace spaceB = new NodeSpace(Map.of(), Map.of(), Map.of("note", "x"), 1, 0, List.of());

        repository.insertMissing(TASK_ID, graph, Map.of("B", spaceB));

        ArgumentCaptor<TaskGraphNodeDto> nodeCaptor = ArgumentCaptor.forClass(TaskGraphNodeDto.class);
        verify(nodeService, times(1)).save(nodeCaptor.capture());
        TaskGraphNodeDto inserted = nodeCaptor.getValue();
        assertThat(inserted.getNodeId()).isEqualTo("B");
        assertThat(inserted.getState()).isNotBlank();
        assertThat(inserted.getId()).isNull();

        ArgumentCaptor<TaskGraphEdgeDto> edgeCaptor = ArgumentCaptor.forClass(TaskGraphEdgeDto.class);
        verify(edgeService).save(edgeCaptor.capture());
        assertThat(edgeCaptor.getValue().getFromNodeId()).isEqualTo("A");
        assertThat(edgeCaptor.getValue().getToNodeId()).isEqualTo("B");
    }

    private static TaskGraph graph(TaskNode... nodes) {
        TaskGraph graph = new TaskGraph();
        graph.setNodes(List.of(nodes));
        return graph;
    }

    private static TaskNode node(String id, NodeStatus status) {
        TaskNode node = new TaskNode();
        node.setNodeId(id);
        node.setStatus(status);
        return node;
    }

    private static TaskGraphNodeVo nodeVo(String nodeId, long id) {
        TaskGraphNodeVo vo = new TaskGraphNodeVo();
        vo.setId(id);
        vo.setTaskId(TASK_ID);
        vo.setNodeId(nodeId);
        vo.setStatus(NodeStatus.READY.name());
        vo.setTargetClaimed(false);
        vo.setProviderInserted(false);
        vo.setDelegateDepth(0);
        return vo;
    }
}
