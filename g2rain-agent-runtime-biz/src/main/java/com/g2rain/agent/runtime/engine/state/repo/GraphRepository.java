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

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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
     * 节点标题的长度上限（列宽 256）。
     */
    private static final int NODE_TITLE_MAX = 256;

    /**
     * 节点目标的长度上限（列宽 2000）。
     */
    private static final int NODE_OBJECTIVE_MAX = 2000;

    /**
     * 节点失败说明的长度上限（列宽 1000）。
     */
    private static final int NODE_ERROR_MESSAGE_MAX = 1000;

    /**
     * 边去重键中起点与终点之间的分隔符，避免不同 id 拼接后撞键。
     */
    private static final String EDGE_KEY_SEPARATOR = "|";

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

            // 主键按规划顺序生成：升序还原即计划顺序（查询默认按主键倒序）
            Map<String, TaskNode> byId = new LinkedHashMap<>();
            for (TaskGraphNodeVo vo : nodeVos.stream().sorted(Comparator.comparing(TaskGraphNodeVo::getId)).toList()) {
                TaskNode node = new TaskNode();
                node.setNodeId(vo.getNodeId());
                node.setTitle(vo.getTitle());
                node.setObjective(vo.getObjective());
                node.setSkillKey(vo.getSkillKey());
                node.setTarget(vo.getTarget());
                node.setTargetClaimed(Boolean.TRUE.equals(vo.getTargetClaimed()));
                node.setProviderInserted(Boolean.TRUE.equals(vo.getProviderInserted()));
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
     * 轮末写图：节点壳 + 已打开节点的 state。
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
     * 插入库中尚不存在的节点与边；已有的节点行不动。
     *
     * @param taskId 任务 id
     * @param graph  执行图
     * @param spaces 本轮打开过的节点空间，新插入的节点有空间时一并写入 state
     */
    public void insertMissing(String taskId, TaskGraph graph, Map<String, NodeSpace> spaces) {
        Set<String> existing = listNodes(taskId).stream()
            .map(TaskGraphNodeVo::getNodeId)
            .collect(Collectors.toSet());
        for (TaskNode node : graph.getNodes()) {
            if (existing.contains(node.getNodeId())) {
                continue;
            }
            TaskGraphNodeDto dto = newNodeDto(taskId, node);
            applyNodeTimes(dto, node);
            NodeSpace space = spaces.get(node.getNodeId());
            if (Objects.nonNull(space)) {
                dto.setState(RepoSupport.toJson(space));
            }
            taskGraphNodeService.save(dto);
        }
        saveEdges(taskId, graph.getEdges());
    }

    /**
     * 按图节点 upsert {@code task_graph_node}；已有行与本轮内容一致时跳过，不再逐行重写。
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
                dto.setTitle(RuntimeUtils.clip(node.getTitle(), NODE_TITLE_MAX));
                dto.setObjective(RuntimeUtils.clip(node.getObjective(), NODE_OBJECTIVE_MAX));
                dto.setSkillKey(node.getSkillKey());
                dto.setTarget(node.getTarget());
                dto.setTargetClaimed(node.isTargetClaimed());
                dto.setProviderInserted(node.isProviderInserted());
                dto.setStatus(node.getStatus().name());
                dto.setResult(RuntimeUtils.clip(node.getResult(), NODE_RESULT_MAX));
                dto.setDelegateDepth(node.getDelegateDepth());
                dto.setErrorCode(node.getErrorCode());
                dto.setErrorMessage(RuntimeUtils.clip(node.getErrorMessage(), NODE_ERROR_MESSAGE_MAX));
            } else {
                dto = newNodeDto(taskId, node);
            }
            applyNodeTimes(dto, node);
            // 本轮打开过的节点才同步节点空间（含输入、产出，冷恢复读此列）
            NodeSpace space = spaces.get(node.getNodeId());
            if (Objects.nonNull(space)) {
                dto.setState(RepoSupport.toJson(space));
            }
            if (Objects.nonNull(old) && dto.equals(TaskGraphNodeConverter.INSTANCE.vo2dto(old))) {
                continue;
            }

            taskGraphNodeService.save(dto);
        }
    }

    /**
     * 新节点行 DTO：与 {@link #saveNodes} 插入分支字段一致。
     *
     * @param taskId 任务 id
     * @param node   图节点
     * @return 待插入 DTO
     */
    private static TaskGraphNodeDto newNodeDto(String taskId, TaskNode node) {
        TaskGraphNodeDto dto = TaskGraphNodeConverter.INSTANCE.node2dto(node);
        dto.setTaskId(taskId);
        dto.setTargetClaimed(node.isTargetClaimed());
        dto.setProviderInserted(node.isProviderInserted());
        dto.setTitle(RuntimeUtils.clip(node.getTitle(), NODE_TITLE_MAX));
        dto.setObjective(RuntimeUtils.clip(node.getObjective(), NODE_OBJECTIVE_MAX));
        dto.setResult(RuntimeUtils.clip(node.getResult(), NODE_RESULT_MAX));
        dto.setErrorMessage(RuntimeUtils.clip(node.getErrorMessage(), NODE_ERROR_MESSAGE_MAX));
        return dto;
    }

    /**
     * 节点起止时间写入 DTO（列精度到秒）。
     *
     * @param dto  节点 DTO
     * @param node 图节点
     */
    private static void applyNodeTimes(TaskGraphNodeDto dto, TaskNode node) {
        if (Objects.nonNull(node.getStartedAt())) {
            dto.setStartedTime(RuntimeUtils.timeOf(node.getStartedAt()).truncatedTo(ChronoUnit.SECONDS));
        }
        if (Objects.nonNull(node.getFinishedAt())) {
            dto.setFinishedTime(RuntimeUtils.timeOf(node.getFinishedAt()).truncatedTo(ChronoUnit.SECONDS));
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
            .map(vo -> vo.getFromNodeId() + EDGE_KEY_SEPARATOR + vo.getToNodeId())
            .collect(Collectors.toSet());

        for (TaskEdge edge : edges) {
            String key = edge.getFrom() + EDGE_KEY_SEPARATOR + edge.getTo();
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
