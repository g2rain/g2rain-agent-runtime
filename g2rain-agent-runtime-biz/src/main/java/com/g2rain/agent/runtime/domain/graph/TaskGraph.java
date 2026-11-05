package com.g2rain.agent.runtime.domain.graph;


import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一次任务的执行图：节点、依赖边及其调度查询规则的集合。
 * <p>
 * 能力概览：
 * <ul>
 *   <li>按节点 dependsOn 自动补边</li>
 *   <li>按 ID 查找、找挂起节点、唤醒焦点</li>
 *   <li>{@link #nextWave(String)}：算出本波可跑节点，本轮焦点优先（当前实现单节点调度）</li>
 *   <li>{@link #resumeAwaiting()}：委派子节点终态后回醒父节点，不连锁失败</li>
 * </ul>
 * 方法均同步或依赖可变列表，多线程场景需外部串行化访问。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Getter
@Setter
@NoArgsConstructor
public class TaskGraph {

    /**
     * 图上的全部节点；列表顺序用于稳定遍历，不等同于依赖拓扑顺序。
     */
    private List<TaskNode> nodes = new ArrayList<>();

    /**
     * 依赖边集合；调用 {@link #addNode(TaskNode)} 时按节点依赖自动补充。
     */
    private List<TaskEdge> edges = new ArrayList<>();

    /**
     * 加入一个节点，并按其 {@link TaskNode#getDependsOn()} 为每条依赖补一条边。
     * <p>边界：不校验 from 是否已存在；重复 add 同一节点会导致重复边。</p>
     *
     * @param node 待加入节点（须已设好 nodeId 与 dependsOn）
     */
    public synchronized void addNode(TaskNode node) {
        // 入图同时按 dependsOn 补边，调度只认边不回头改依赖列表
        nodes.add(node);
        node.getDependsOn().forEach(from -> edges.add(
            new TaskEdge(from, node.getNodeId())
        ));
    }

    /**
     * 以该写工具为完成目标、尚未结束的其他节点。
     *
     * <p>规划把写操作分给了谁，就由谁执行；其他节点抢先调用会被写范围门拒绝。</p>
     *
     * @param toolName     写工具注册名
     * @param exceptNodeId 当前节点 ID，不计入
     * @return 第一个匹配节点；没有则 null
     */
    public @Nullable TaskNode targetOwner(String toolName, String exceptNodeId) {
        if (Collections.isEmpty(nodes)) {
            return null;
        }

        return nodes.stream()
            .filter(node -> !node.getNodeId().equals(exceptNodeId))
            .filter(node -> toolName.equals(node.getTarget()) && !node.getStatus().isTerminal())
            .findFirst()
            .orElse(null);
    }

    /**
     * 按节点 ID 查找。
     *
     * @param nodeId 节点 ID；空白则直接返回 null
     * @return 第一个匹配节点；图空或不存在则 null
     */
    public @Nullable TaskNode find(String nodeId) {
        // 空白 ID 无法定位
        if (Strings.isBlank(nodeId)) {
            return null;
        }

        if (Collections.isEmpty(nodes)) {
            return null;
        }

        return nodes.stream().filter(node ->
            nodeId.equals(node.getNodeId())
        ).findFirst().orElse(null);
    }

    /**
     * 查看（不改状态）当前应关注的挂起节点。
     * <p>
     * focusNodeId 非空：只认「ID 匹配且仍 SUSPENDED」的那一个；
     * focusNodeId 空白：退回 {@link #findSuspended()} 找图上第一个挂起节点。
     * </p>
     *
     * @param focusNodeId 期望的焦点节点 ID，可为 null/空白
     * @return 匹配的挂起节点；找不到则 null
     */
    public @Nullable TaskNode peekSuspendedFocus(@Nullable String focusNodeId) {
        // 空图没有挂起焦点，避免对流空集合
        if (Collections.isEmpty(nodes)) {
            return null;
        }

        // 未指定焦点：退回图上第一个 SUSPENDED
        if (Strings.isBlank(focusNodeId)) {
            return findSuspended();
        }

        // 指定焦点：ID 匹配且仍为 SUSPENDED 才算
        return nodes.stream()
            .filter(o -> focusNodeId.equals(o.getNodeId()))
            .filter(o -> o.getStatus().isSuspended())
            .findFirst()
            .orElse(null);
    }

    /**
     * 找图上第一个状态为 SUSPENDED 的节点。
     *
     * @return 挂起节点；图空或没有挂起则 null
     */
    public @Nullable TaskNode findSuspended() {
        if (Collections.isEmpty(nodes)) {
            return null;
        }

        return nodes.stream().filter(node ->
            node.getStatus().isSuspended()
        ).findFirst().orElse(null);
    }

    /**
     * 把指定焦点节点从挂起改为 READY，并返回该节点。
     * <p>
     * 仅当 ID 匹配且当前仍是 SUSPENDED 时生效；已非挂起则跳过继续找。
     * 边界：focusNodeId 空白、图空、找不到匹配挂起节点 → 返回 null，不改任何状态。
     * </p>
     *
     * @param focusNodeId 要唤醒的节点 ID
     * @return 被改为 READY 的节点；未改动则 null
     */
    public @Nullable TaskNode focusSuspended(@Nullable String focusNodeId) {
        // 空白焦点 ID 无法唤醒
        if (Strings.isBlank(focusNodeId)) {
            return null;
        }

        if (Collections.isEmpty(nodes)) {
            return null;
        }

        // 遍历找匹配的挂起节点
        for (TaskNode node : nodes) {
            // ID 对不上就跳过
            if (!focusNodeId.equals(node.getNodeId())) {
                continue;
            }

            // 已经不是挂起，不能当焦点唤醒
            if (node.getStatus().isUnsuspended()) {
                continue;
            }

            // SUSPENDED → READY，本轮可继续调度
            node.setStatus(NodeStatus.READY);
            return node;
        }

        // 没命中任何可唤醒节点
        return null;
    }

    /**
     * 与 {@link #focusSuspended} 相同的就绪逻辑，但不返回节点（仅副作用）。
     * <p>focusNodeId 空白或未命中挂起节点时静默返回。</p>
     *
     * @param focusNodeId 要置为 READY 的挂起节点 ID
     */
    public void readySuspendedFocus(@Nullable String focusNodeId) {
        // 空白焦点 ID：静默不做
        if (Strings.isBlank(focusNodeId)) {
            return;
        }

        if (Collections.isEmpty(nodes)) {
            return;
        }

        // 与 focusSuspended 同逻辑，只改状态不回传节点
        for (TaskNode node : nodes) {
            // ID 对不上就跳过
            if (!focusNodeId.equals(node.getNodeId())) {
                continue;
            }

            // 非挂起则不能改 READY
            if (node.getStatus().isUnsuspended()) {
                continue;
            }

            // SUSPENDED → READY
            node.setStatus(NodeStatus.READY);
            return;
        }
    }

    /**
     * 计算下一波可执行节点，并在必要时把 PENDING 升为 READY。
     * <p>
     * 步骤：先 {@link #resumeAwaiting()}；再收集已 unlocksDownstream 的节点 ID；
     * 焦点节点排在最前，其余按列表顺序遍历，跳过不可调度的，依赖未齐的也跳过；依赖齐的 PENDING 升 READY 后加入波。
     * 当前实现在加入第一个节点后 break（单节点调度）；要同级并行时去掉该 break。
     * 焦点优先保证本轮唤醒的节点（确认审批、回答追问）先执行，而不是被列表中靠前的其他节点抢先。
     * </p>
     *
     * @param focusNodeId 本轮焦点节点 ID；可空
     * @return 本波节点列表；图空则为空列表（不可变）
     */
    public List<TaskNode> nextWave(@Nullable String focusNodeId) {
        if (Collections.isEmpty(this.nodes)) {
            return List.of();
        }

        // 先让等委派的父节点有机会回醒
        resumeAwaiting();

        // 已完成或跳过的节点，视为下游依赖已满足
        Set<String> completed = nodes.stream()
            .filter(o -> o.getStatus().unlocksDownstream())
            .map(TaskNode::getNodeId)
            .collect(Collectors.toSet());

        // 收集本波可跑节点
        List<TaskNode> wave = new ArrayList<>();
        for (TaskNode node : focusFirst(focusNodeId)) {
            NodeStatus status = node.getStatus();
            // RUNNING / SUSPENDED / 终态等一律不入波
            if (status.isNotSchedulable()) {
                continue;
            }

            // 还有上游没放开，继续等
            if (!dependencySatisfied(node, completed)) {
                continue;
            }

            // 依赖齐了：PENDING → READY，再入波
            if (status.isPending()) {
                node.setStatus(NodeStatus.READY);
            }

            // 本节点可调度，加入本波
            wave.add(node);

            /*
             * 单节点调度
             * 要同级并行时去掉此 break
             */
            break;
        }

        // 交出本波列表（可能空）
        return wave;
    }

    /**
     * 取波遍历顺序：焦点节点在前，其余保持列表顺序。
     *
     * @param focusNodeId 本轮焦点节点 ID；可空或不在图中时直接返回原列表
     * @return 遍历顺序
     */
    private List<TaskNode> focusFirst(@Nullable String focusNodeId) {
        TaskNode focus = Strings.isBlank(focusNodeId) ? null : find(focusNodeId);
        if (Objects.isNull(focus)) {
            return nodes;
        }

        List<TaskNode> ordered = new ArrayList<>(nodes.size());
        ordered.add(focus);
        nodes.stream().filter(node -> node != focus).forEach(ordered::add);
        return ordered;
    }

    /**
     * 判断节点的所有依赖是否已经满足。
     *
     * <p>普通依赖要求出现在本轮已完成集合中；委派依赖则要求子节点已经进入终态。</p>
     *
     * @param node      待判断节点
     * @param completed 本轮已完成的普通依赖节点 id
     * @return 所有依赖都满足时返回 {@code true}
     */
    private boolean dependencySatisfied(TaskNode node, Set<String> completed) {
        for (String depId : node.getDependsOn()) {
            if (delegateUpstream(node.getNodeId(), depId)) {
                TaskNode dep = find(depId);
                if (Objects.isNull(dep) || !dep.getStatus().isTerminal()) {
                    return false;
                }
            } else if (!completed.contains(depId)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 判断指定依赖是否通过委派边连接到当前节点。
     *
     * @param nodeId 当前节点 id
     * @param depId  依赖节点 id
     * @return 存在对应 DELEGATE 边时返回 {@code true}
     */
    private boolean delegateUpstream(String nodeId, String depId) {
        return edges.stream().anyMatch(o ->
            o.isDelegate() && depId.equals(o.getFrom()) && nodeId.equals(o.getTo())
        );
    }

    /**
     * 扫描 WAITING_DELEGATE 发起节点：前置终态后回 READY；前置缺失 → FAILED（{@code DELEGATE_BROKEN}）。
     * <p>
     * 前置 FAILED / CANCELLED / SKIPPED 不连锁失败，仅回 READY（观察由 open 写 {@code delegateResult}）。
     * </p>
     */
    public void resumeAwaiting() {
        if (Collections.isEmpty(nodes)) {
            return;
        }

        for (TaskNode parent : nodes) {
            // 只回醒等委派的发起节点
            if (parent.getStatus() != NodeStatus.WAITING_DELEGATE) {
                continue;
            }

            List<String> childIds = edges.stream()
                .filter(TaskEdge::isDelegate)
                .filter(edge -> parent.getNodeId().equals(edge.getTo()))
                .map(TaskEdge::getFrom)
                .toList();
            // 委派边指向缺失节点：链已坏，父节点失败且不连锁子节点
            if (Collections.isEmpty(childIds) || childIds.stream().anyMatch(id -> Objects.isNull(find(id)))) {
                parent.setStatus(NodeStatus.FAILED);
                parent.setErrorCode("DELEGATE_BROKEN");
                parent.setErrorMessage("委派链损坏：前置节点缺失");
                continue;
            }

            boolean childStillOpen = childIds.stream()
                .map(this::find)
                .anyMatch(o -> Objects.nonNull(o) && !o.getStatus().isTerminal());
            if (!childStillOpen) {
                // 子节点均终态：父节点回 READY，FAILED 子节点不连锁失败
                parent.setStatus(NodeStatus.READY);
            }
        }
    }

    /**
     * 沿依赖边（含委派）向上追溯的全部上游节点，近的在前。
     *
     * @param nodeId 当前节点 id
     * @return 上游节点 id；没有为空列表
     */
    public List<String> upstream(String nodeId) {
        List<String> found = new ArrayList<>();
        Set<String> seen = new HashSet<>(Set.of(nodeId));
        Deque<String> queue = new ArrayDeque<>(List.of(nodeId));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            edges.stream()
                .filter(edge -> current.equals(edge.getTo()))
                .map(TaskEdge::getFrom)
                .filter(seen::add)
                .forEach(from -> {
                    found.add(from);
                    queue.add(from);
                });
        }
        return found;
    }
}
