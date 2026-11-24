package com.g2rain.agent.runtime.engine.flow.hydrate;

import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultHydrator} 版本锁定测试。
 *
 * @author alpha
 * @since 2026/11/17
 */
class DefaultHydratorTest {

    private static final long AGENT_ID = 9L;

    private static final String SKILL_KEY = "cms.article";

    @Test
    void hydrate_newTask_shouldPassEmptyPinsAndRecordReturnedPins() {
        BundleLoader bundleLoader = mock(BundleLoader.class);
        when(bundleLoader.load(eq(AGENT_ID), eq(Map.of())))
            .thenReturn(List.of(skill("pin-a")));
        TaskMetadata task = new TaskMetadata();
        TurnContext context = contextWith(task);

        new DefaultHydrator(bundleLoader).hydrate(context);

        verify(bundleLoader).load(AGENT_ID, Map.of());
        assertEquals(Map.of(SKILL_KEY, "pin-a"), task.getSpecPins());
    }

    @Test
    void hydrate_subsequentRound_shouldPassLockedPinsAndNotOverwrite() {
        BundleLoader bundleLoader = mock(BundleLoader.class);
        Map<String, String> locked = new LinkedHashMap<>(Map.of(SKILL_KEY, "pin-a"));
        when(bundleLoader.load(eq(AGENT_ID), eq(Map.copyOf(locked))))
            .thenReturn(List.of(skill("pin-b")));
        TaskMetadata task = new TaskMetadata();
        task.setSpecPins(locked);
        TurnContext context = contextWith(task);

        new DefaultHydrator(bundleLoader).hydrate(context);

        verify(bundleLoader).load(AGENT_ID, Map.of(SKILL_KEY, "pin-a"));
        assertEquals("pin-a", task.getSpecPins().get(SKILL_KEY));
    }

    private static TurnContext contextWith(TaskMetadata task) {
        TurnScope turn = mock(TurnScope.class);
        when(turn.task()).thenReturn(Optional.of(task));
        when(turn.graph()).thenReturn(new TaskGraph());
        TurnContext context = new TurnContext(mock(TurnRequest.class), turn);
        AgentSelection agent = new AgentSelection();
        agent.setAgentId(AGENT_ID);
        context.setAgentSelection(agent);
        return context;
    }

    private static Skill skill(String pin) {
        return new Skill(SKILL_KEY, SKILL_KEY, List.of(), null, null, new SkillContract(), pin);
    }
}
