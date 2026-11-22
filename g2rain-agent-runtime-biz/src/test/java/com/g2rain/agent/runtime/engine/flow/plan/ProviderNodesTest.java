package com.g2rain.agent.runtime.engine.flow.plan;

import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.InputSpec;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.spec.SourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ProviderNodes} 前置节点补全测试。
 *
 * @author alpha
 * @since 2026/11/16
 */
class ProviderNodesTest {

    private static final String PUBLISH = "publish";

    private static final String LIST = "list";

    /** T2：alreadyBound — session 有值 / shared 为空 / prior。 */
    @Test
    void t2_alreadyBound_sessionSharedPrior() {
        TurnScope turn = mock(TurnScope.class);
        when(turn.sessionShared()).thenReturn(Map.of("a",
            new Sourced<>("1", new Source(SourceType.SESSION, "session", "a"), false)));
        when(turn.shared()).thenReturn(Map.of());

        InputSpec session = new InputSpec();
        session.setFrom("session.a");
        InputSpec shared = new InputSpec();
        shared.setFrom("shared.a");
        InputSpec prior = new InputSpec();
        prior.setFrom("prior.a");

        assertTrue(ProviderNodes.alreadyBound(turn, session));
        assertFalse(ProviderNodes.alreadyBound(turn, shared));
        assertFalse(ProviderNodes.alreadyBound(turn, prior));
    }

    @Test
    void expand_shouldInsertNewProviderWhenReuseWouldFormCycle() {
        TaskNode consumer = node("c", PUBLISH);
        consumer.setStatus(NodeStatus.READY);
        TaskNode downstream = node("p", LIST);
        downstream.getDependsOn().add(consumer.getNodeId());
        TaskGraph graph = new TaskGraph();
        graph.getNodes().add(consumer);
        graph.getNodes().add(downstream);

        ProviderNodes.expand(graph, Map.of(PUBLISH, publishSkill(), LIST, skill(LIST, new SkillContract())),
            mock(TurnScope.class));

        assertEquals(3, graph.getNodes().size());
        assertFalse(consumer.getDependsOn().contains(downstream.getNodeId()));
        TaskNode provider = graph.getNodes().getLast();
        assertEquals(LIST, provider.getSkillKey());
        assertEquals(List.of(provider.getNodeId()), consumer.getDependsOn());
    }

    private static TaskNode node(String nodeId, String skillKey) {
        TaskNode node = new TaskNode();
        node.setNodeId(nodeId);
        node.setSkillKey(skillKey);
        return node;
    }

    private static Skill publishSkill() {
        InputSpec input = new InputSpec();
        input.setProvider(LIST);
        SkillContract contract = new SkillContract();
        contract.setInputs(Map.of("articles", input));
        return skill(PUBLISH, contract);
    }

    private static Skill skill(String skillKey, SkillContract contract) {
        return new Skill(skillKey, skillKey, List.of(), null, null, contract, null);
    }
}
