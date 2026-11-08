package com.g2rain.agent.runtime.engine.flow.plan;


import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskEdge;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.plan.PlanStep;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.common.utils.Collections;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PlanGraphs：计划步骤校验与落成任务图的工具方法。
 * <p>
 * 校验依赖是否合法、是否成环；通过后把步骤转成 {@link TaskGraph}，并找出首个 READY 节点。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class PlanGraphs {

    /**
     * 禁止实例化：只提供静态校验与落图方法。
     */
    private PlanGraphs() {

    }

    /**
     * 步骤列表是否可作为计划：依赖指向存在且无自环、整图无环。
     *
     * @param steps 计划步骤
     * @return 合法则为 true
     */
    static boolean valid(List<PlanStep> steps) {
        Set<String> ids = steps.stream().map(PlanStep::nodeId)
            .collect(Collectors.toCollection(HashSet::new));
        return dependsOnValid(steps, ids) && !hasCycle(steps);
    }

    /**
     * 把计划步骤落成任务图：生成运行时节点 ID，并按 dependsOn 连边。
     *
     * <p>非首步且未声明依赖的步骤接到前一步之后：图按单节点调度，补边不改变执行顺序，
     * 只让后续步骤能看到前一步的证据。开启同级并行时需重新评估此规则。</p>
     *
     * @param steps 已校验的计划步骤
     * @return 可执行任务图
     */
    static TaskGraph materialize(List<PlanStep> steps) {
        TaskGraph graph = new TaskGraph();
        Map<String, String> stepIdToNodeId = new HashMap<>();
        List<TaskNode> nodes = new ArrayList<>();
        for (PlanStep step : steps) {
            String nodeId = RuntimeUtils.uuid();
            stepIdToNodeId.put(step.nodeId(), nodeId);
            nodes.add(TaskNode.from(nodeId, step));
        }

        List<TaskEdge> edges = new ArrayList<>();
        for (int i = 0, n = steps.size(); i < n; i++) {
            PlanStep step = steps.get(i);
            TaskNode node = nodes.get(i);
            List<String> fromIds = step.dependsOn().isEmpty() && i > 0
                ? List.of(nodes.get(i - 1).getNodeId())
                : step.dependsOn().stream().map(stepIdToNodeId::get).toList();
            for (String fromId : fromIds) {
                node.getDependsOn().add(fromId);
                edges.add(new TaskEdge(fromId, node.getNodeId()));
            }
        }

        graph.setNodes(nodes);
        graph.setEdges(edges);
        return graph;
    }

    /**
     * 每个步骤的 dependsOn 都必须指向其它已有步骤 ID。
     *
     * @param steps 计划步骤
     * @param ids   步骤 ID 集合
     * @return 依赖合法则为 true
     */
    private static boolean dependsOnValid(List<PlanStep> steps, Set<String> ids) {
        return steps.stream().allMatch(step -> step.dependsOn().stream().allMatch(
            id -> !id.equals(step.nodeId()) && ids.contains(id)
        ));
    }

    /**
     * 拓扑计数检测是否存在环：看完的节点数少于步骤总数则有环。
     *
     * @param steps 计划步骤
     * @return 存在环则为 true
     */
    private static boolean hasCycle(List<PlanStep> steps) {
        Map<String, Integer> inDegree = steps.stream().collect(Collectors.toMap(
            PlanStep::nodeId, _ -> 0
        ));
        Map<String, List<String>> adjacency = steps.stream().collect(Collectors.toMap(
            PlanStep::nodeId, _ -> new ArrayList<>()
        ));

        steps.forEach(node -> node.dependsOn().forEach(dep -> {
            adjacency.get(dep).add(node.nodeId());
            inDegree.merge(node.nodeId(), 1, Integer::sum);
        }));

        Queue<String> queue = inDegree.entrySet().stream()
            .filter(e -> e.getValue() == 0)
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(ArrayDeque::new));

        int dequeuedCount = 0;
        while (!queue.isEmpty()) {
            String id = queue.poll();
            dequeuedCount++;
            for (String next : adjacency.get(id)) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }

        return dequeuedCount != steps.size();
    }

    /**
     * 取出图上第一个状态为 READY 的节点 ID，作为开跑焦点。
     *
     * @param graph 任务图
     * @return 焦点节点 ID；无 READY 节点则为 {@code null}
     */
    static @Nullable String firstReadyNodeId(TaskGraph graph) {
        if (Collections.isEmpty(graph.getNodes())) {
            return null;
        }

        return graph.getNodes().stream()
            .filter(n -> n.getStatus() == NodeStatus.READY)
            .map(TaskNode::getNodeId)
            .findFirst()
            .orElse(null);
    }
}
