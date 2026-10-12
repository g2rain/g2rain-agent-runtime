package com.g2rain.agent.runtime.engine.state.repo;

import com.g2rain.agent.runtime.converter.TaskGraphEdgeConverter;
import com.g2rain.agent.runtime.converter.TaskGraphNodeConverter;
import com.g2rain.agent.runtime.domain.graph.EdgeType;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskEdge;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.task.NodeSpace;
import com.g2rain.agent.runtime.dto.TaskGraphEdgeSelectDto;
import com.g2rain.agent.runtime.dto.TaskGraphNodeDto;
import com.g2rain.agent.runtime.dto.TaskGraphNodeSelectDto;
import com.g2rain.agent.runtime.service.TaskGraphEdgeService;
import com.g2rain.agent.runtime.service.TaskGraphNodeService;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.vo.TaskGraphEdgeVo;
import com.g2rain.agent.runtime.vo.TaskGraphNodeVo;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 图仓储：{@code task_graph_node}、{@code task_graph_edge} 的读写。
 *
 * @author alpha
 * @since 2026/10/12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphRepository {

    /**
     * 节点结论的长度上限（列宽 2000）。
     */
    private static final int NODE_RESULT_MAX = 2000;

    /**
     * 图节点表服务。
     */
    private final TaskGraphNodeService taskGraphNodeService;

    /**
     * 图边表服务。
     */
    private final TaskGraphEdgeService taskGraphEdgeService;

    /**
     * 从库读执行图：{@code task_graph_node} + {@code task_graph_edge} → {@link TaskGraph}。
     * <p>无节点返回 {@code null}；边会回填各节点 dependsOn。</p>
     *
     * @param taskId 任务 id
     * @return 节点与边组成的执行图；无节点为 {@code null}
     */
    public @Nullable TaskGraph load(String taskId) {
        try {
            List<TaskGraphNodeVo> nodeVos = listNodes(taskId);
            if (Collections.isEmpty(nodeVos)) {
                return null;
            }

            // 节点行按库序还原为 TaskNode，保持计划顺序
            Map<String, TaskNode> byId = new LinkedHashMap<>();
            for (TaskGraphNodeVo vo : nodeVos) {
                TaskNode node = new TaskNode();
                node.setNodeId(vo.getNodeId());
                node.setTitle(vo.getTitle());
                node.setObjective(vo.getObjective());
                node.setSkillKey(vo.getSkillKey());
                node.setTarget(vo.getTarget());
                node.setTargetClaimed(Boolean.TRUE.equals(vo.getTargetClaimed()));
                node.setResult(vo.getResult());
                if (Objects.nonNull(vo.getDelegateDepth())) {
                    node.setDelegateDepth(vo.getDelegateDepth());
                }
                if (Strings.isNotBlank(vo.getStatus())) {
                    node.setStatus(NodeStatus.valueOf(vo.getStatus()));
                }
                node.setErrorCode(vo.getErrorCode());
                node.setErrorMessage(vo.getErrorMessage());
                if (Objects.nonNull(vo.getStartedTime())) {
                    node.setStartedAt(RuntimeUtils.epochOf(vo.getStartedTime()));
                }
                if (Objects.nonNull(vo.getFinishedTime())) {
                    node.setFinishedAt(RuntimeUtils.epochOf(vo.getFinishedTime()));
                }
                byId.put(node.getNodeId(), node);
            }

            // 边行还原为 TaskEdge，并回填目标节点的 dependsOn
            List<TaskEdge> edges = new ArrayList<>();
            for (TaskGraphEdgeVo vo : listEdges(taskId)) {
                EdgeType type = Strings.isBlank(vo.getEdgeType())
                    ? EdgeType.NORMAL
                    : EdgeType.valueOf(vo.getEdgeType());
                edges.add(new TaskEdge(vo.getFromNodeId(), vo.getToNodeId(), type));
                TaskNode to = byId.get(vo.getToNodeId());
                if (Objects.nonNull(to)) {
                    to.getDependsOn().add(vo.getFromNodeId());
                }
            }

            TaskGraph graph = new TaskGraph();
            graph.setNodes(new ArrayList<>(byId.values()));
            graph.setEdges(edges);
            return graph;
        } catch (Exception e) {
            log.warn("[GraphRepository] load failed taskId={}", taskId, e);
            throw RepoSupport.unavailable(e);
        }
    }

    /**
     * 从 {@code task_graph_node.state} 回灌 NodeSpace。
     *
     * @param taskId 任务 id
     * @param nodeId 节点 id
     * @return 节点空间；没有行或未落过空间为 {@code null}
     */
    public @Nullable NodeSpace loadSpace(String taskId, String nodeId) {
        TaskGraphNodeVo vo = findNode(taskId, nodeId);
        if (Objects.isNull(vo) || Strings.isBlank(vo.getState())) {
            return null;
        }
        return RepoSupport.JSON.str2obj(vo.getState(), NodeSpace.class);
    }

    /**
     * 轮末写图：节点壳 + 已打开节点的 inputs / outputs / state。
     *
     * @param taskId 任务 id
     * @param graph  执行图
     * @param spaces 已打开节点空间
     */
    public void save(String taskId, TaskGraph graph, Map<String, NodeSpace> spaces) {
        saveNodes(taskId, graph.getNodes(), spaces);
        saveEdges(taskId, graph.getEdges());
    }

    /**
     * 按图节点 upsert {@code task_graph_node}。
     *
     * @param taskId 任务 id
     * @param nodes  图节点
     * @param spaces 已打开节点空间
     */
    private void saveNodes(String taskId, List<TaskNode> nodes, Map<String, NodeSpace> spaces) {
        var byNodeId = listNodes(taskId).stream().collect(Collectors.toMap(
            TaskGraphNodeVo::getNodeId, vo -> vo, (a, _) -> a
        ));

        for (TaskNode node : nodes) {
            TaskGraphNodeVo old = byNodeId.get(node.getNodeId());
            TaskGraphNodeDto dto;
            // 已有行只覆盖可变字段，保留主键与创建信息；新节点整行转换
            if (Objects.nonNull(old)) {
                dto = TaskGraphNodeConverter.INSTANCE.vo2dto(old);
                dto.setTitle(node.getTitle());
                dto.setObjective(node.getObjective());
                dto.setSkillKey(node.getSkillKey());
                dto.setTarget(node.getTarget());
                dto.setTargetClaimed(node.isTargetClaimed());
                dto.setStatus(node.getStatus().name());
                dto.setResult(RuntimeUtils.clip(node.getResult(), NODE_RESULT_MAX));
                dto.setDelegateDepth(node.getDelegateDepth());
                dto.setErrorCode(node.getErrorCode());
                dto.setErrorMessage(node.getErrorMessage());
            } else {
                dto = TaskGraphNodeConverter.INSTANCE.node2dto(node);
                dto.setTaskId(taskId);
                dto.setTargetClaimed(node.isTargetClaimed());
                dto.setResult(RuntimeUtils.clip(node.getResult(), NODE_RESULT_MAX));
            }
            if (Objects.nonNull(node.getStartedAt())) {
                dto.setStartedTime(RuntimeUtils.timeOf(node.getStartedAt()));
            }
            if (Objects.nonNull(node.getFinishedAt())) {
                dto.setFinishedTime(RuntimeUtils.timeOf(node.getFinishedAt()));
            }
            // 本轮打开过的节点才同步空间（输入、产出与完整 NodeSpace）
            NodeSpace space = spaces.get(node.getNodeId());
            if (Objects.nonNull(space)) {
                dto.setInputs(RepoSupport.toJson(space.inputs()));
                dto.setOutputs(RepoSupport.toJson(space.outputs()));
                dto.setState(RepoSupport.toJson(space));
            }
            taskGraphNodeService.save(dto);
        }
    }

    /**
     * 插入库中尚不存在的图边。
     *
     * @param taskId 任务 id
     * @param edges  图边
     */
    private void saveEdges(String taskId, List<TaskEdge> edges) {
        // 已落库边集合，避免重复 insert
        Set<String> knownKeys = listEdges(taskId).stream()
            .map(vo -> vo.getFromNodeId() + vo.getToNodeId())
            .collect(Collectors.toSet());

        for (TaskEdge edge : edges) {
            String key = edge.getFrom() + edge.getTo();
            if (knownKeys.contains(key)) {
                continue;
            }

            var dto = TaskGraphEdgeConverter.INSTANCE.edge2dto(edge);
            dto.setTaskId(taskId);
            taskGraphEdgeService.save(dto);
            knownKeys.add(key);
        }
    }

    /**
     * 列出任务图节点行。
     *
     * @param taskId 任务 id
     * @return 节点行；没有为空列表
     */
    private List<TaskGraphNodeVo> listNodes(String taskId) {
        TaskGraphNodeSelectDto query = new TaskGraphNodeSelectDto();
        query.setTaskId(taskId);
        return taskGraphNodeService.selectList(query);
    }

    /**
     * 列出任务图边行。
     *
     * @param taskId 任务 id
     * @return 边行；没有为空列表
     */
    private List<TaskGraphEdgeVo> listEdges(String taskId) {
        TaskGraphEdgeSelectDto query = new TaskGraphEdgeSelectDto();
        query.setTaskId(taskId);
        return taskGraphEdgeService.selectList(query);
    }

    /**
     * 按任务与节点 id 读图节点行。
     *
     * @param taskId 任务 id
     * @param nodeId 节点 id
     * @return 行；没有为 {@code null}
     */
    private @Nullable TaskGraphNodeVo findNode(String taskId, String nodeId) {
        return listNodes(taskId).stream()
            .filter(vo -> nodeId.equals(vo.getNodeId()))
            .findFirst()
            .orElse(null);
    }
}
