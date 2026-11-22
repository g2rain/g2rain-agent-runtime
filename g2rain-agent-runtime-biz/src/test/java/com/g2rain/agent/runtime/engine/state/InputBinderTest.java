package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.domain.hydrate.InputSpec;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.EntityEntry;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.spec.SourceType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link InputBinder} from 前缀解析用例 T1。
 *
 * @author alpha
 * @since 2026/11/22
 */
class InputBinderTest {

    /** T1：session / shared / prior / outputs / 未知前缀。 */
    @Test
    void t1_resolve_fromPrefixes() {
        TurnScope turn = mock(TurnScope.class);
        Sourced<String> session = new Sourced<>("s", new Source(SourceType.SESSION, "session", "a"), false);
        Sourced<String> shared = new Sourced<>("t", new Source(SourceType.SHARED, "shared", "a"), false);
        Sourced<String> upstream = new Sourced<>("u", new Source(SourceType.NODE_OUTPUT, "up", "a"), false);
        Sourced<String> byName = new Sourced<>("n", new Source(SourceType.NODE_OUTPUT, "up", "x"), false);
        when(turn.sessionShared()).thenReturn(Map.of("a", session));
        when(turn.shared()).thenReturn(Map.of("a", shared));
        when(turn.prior()).thenReturn(Optional.of(new Prior(List.of(),
            Map.of("article:1", new EntityEntry("文章A", JsonNodeFactory.instance.objectNode(), null, null, 1L, 1)),
            List.of())));

        SkillContract contract = new SkillContract();
        contract.setInputs(Map.of(
            "sessionVal", from("session.a"),
            "sharedVal", from("shared.a"),
            "priorVal", from("prior.article"),
            "outputsVal", from("outputs.a"),
            "unknownVal", from("foo.bar")
        ));
        Map<String, Map<String, Sourced<?>>> upstreamOutputs = Map.of(
            "up", Map.of("a", upstream, "unknownVal", byName)
        );
        var graphNode = new com.g2rain.agent.runtime.domain.graph.TaskNode();
        graphNode.setNodeId("n1");
        graphNode.getDependsOn().add("up");
        when(turn.graph()).thenReturn(graphWith(graphNode));

        Map<String, Sourced<?>> bound = InputBinder.resolve(turn, "n1", contract, upstreamOutputs);

        assertThat(bound.get("sessionVal")).isSameAs(session);
        assertThat(bound.get("sharedVal")).isSameAs(shared);
        assertThat(bound.get("priorVal").value()).isEqualTo("文章A");
        assertThat(bound.get("outputsVal").value()).isEqualTo("u");
        assertThat(bound.get("unknownVal").value()).isEqualTo("n");
    }

    private static InputSpec from(String expression) {
        InputSpec spec = new InputSpec();
        spec.setFrom(expression);
        return spec;
    }

    private static com.g2rain.agent.runtime.domain.graph.TaskGraph graphWith(
        com.g2rain.agent.runtime.domain.graph.TaskNode node) {
        var graph = new com.g2rain.agent.runtime.domain.graph.TaskGraph();
        graph.getNodes().add(node);
        return graph;
    }
}
