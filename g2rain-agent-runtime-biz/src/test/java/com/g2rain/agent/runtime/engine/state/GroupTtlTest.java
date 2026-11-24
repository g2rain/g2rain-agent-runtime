package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.store.KeyTtl;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GroupTtl} 用例 C6。
 *
 * @author alpha
 * @since 2026/11/22
 */
class GroupTtlTest {

    /** C6：元数据 TTL−gap，其余为 TTL，覆盖图中全部节点。 */
    @Test
    void c6_renewals_sentinelOnMetadata_coversAllNodes() {
        RuntimeProperties properties = new RuntimeProperties();
        properties.getState().setTaskTtlRunning(Duration.ofHours(1));
        properties.getState().setSentinelGap(Duration.ofMinutes(10));
        GroupTtl groupTtl = new GroupTtl(properties);

        TaskMetadata meta = new TaskMetadata();
        meta.setTaskId("t1");
        meta.setStatus(TaskStatus.RUNNING);
        TaskGraph graph = new TaskGraph();
        graph.setNodes(List.of(node("n1"), node("n2"), node("n3")));
        ConversationFence fence = new ConversationFence("c1", "u1", "t1", 1L, "trace", 1L);
        DefaultTurnScope turn = TurnScopes.create("1", "u1", "trace", fence, List.of(), List.of(), new HashMap<>(),
            meta, graph, null, new HashMap<>(), null, new HashMap<>(), 60);
        SessionKeys keys = SessionKeys.of(turn);
        Duration ttl = groupTtl.of(meta);

        List<KeyTtl> renewals = groupTtl.renewals(keys, turn, ttl);

        long taskMs = ttl.toMillis();
        long sentinelMs = taskMs - properties.getState().getSentinelGap().toMillis();
        Map<String, Long> byKey = renewals.stream().collect(Collectors.toMap(KeyTtl::key, KeyTtl::ttlMs));
        assertThat(byKey.get(keys.metadata("t1"))).isEqualTo(sentinelMs);
        assertThat(byKey.get(keys.graph("t1"))).isEqualTo(taskMs);
        assertThat(byKey.get(keys.space("t1", "n1"))).isEqualTo(taskMs);
        assertThat(byKey.get(keys.evidence("t1", "n1"))).isEqualTo(taskMs);
        assertThat(byKey.get(keys.space("t1", "n2"))).isEqualTo(taskMs);
        assertThat(byKey.get(keys.evidence("t1", "n2"))).isEqualTo(taskMs);
        assertThat(byKey.get(keys.space("t1", "n3"))).isEqualTo(taskMs);
        assertThat(byKey.get(keys.evidence("t1", "n3"))).isEqualTo(taskMs);
        assertThat(byKey.keySet()).containsExactlyInAnyOrderElementsOf(keys.group("t1", graph));
    }

    private static TaskNode node(String id) {
        TaskNode node = new TaskNode();
        node.setNodeId(id);
        return node;
    }
}
