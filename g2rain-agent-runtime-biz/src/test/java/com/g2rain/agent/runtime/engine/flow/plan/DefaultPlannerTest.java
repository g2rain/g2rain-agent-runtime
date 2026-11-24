package com.g2rain.agent.runtime.engine.flow.plan;

import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.ExecutionMode;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.event.StepEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultPlanner} 指定技能与默认筛选验收。
 *
 * @author alpha
 * @since 2026/11/23
 */
class DefaultPlannerTest {

    private SkillSelector skillSelector;
    private PlanDrafter planDrafter;
    private DefaultPlanner planner;

    @BeforeEach
    void setUp() {
        skillSelector = mock(SkillSelector.class);
        planDrafter = mock(PlanDrafter.class);
        StepEvents stepEvents = mock(StepEvents.class);
        when(stepEvents.start(any(), anyString())).thenReturn("plan-step");
        planner = new DefaultPlanner(skillSelector, planDrafter, stepEvents);
    }

    @Test
    void forcedSkill_skipsSelector_andBuildsSingleReactNode() {
        TurnRequest request = new TurnRequest();
        request.setChannel("web");
        request.setSkillKey("cms.write");
        request.setUserInput("删分类");

        TurnScope turn = mock(TurnScope.class);
        TaskMetadata task = new TaskMetadata();
        task.setTaskId("task-1");
        task.setGoalSummary("删分类");
        when(turn.task()).thenReturn(Optional.of(task));
        when(turn.conversation()).thenReturn(new ConversationFence("c1", "u1", "task-1", 1L, null, 0L));
        when(turn.graph()).thenReturn(new TaskGraph());

        TurnContext ctx = new TurnContext(request, turn);
        ctx.setDecision(TurnDecision.startFresh());
        ctx.setSkills(List.of(reactSkill("cms.read"), reactSkill("cms.write")));

        planner.plan(ctx);

        verify(skillSelector, never()).select(any(), any(), any());
        verify(planDrafter, never()).draft(any(), any(), any(), any());
        ArgumentCaptor<TaskGraph> graphCaptor = ArgumentCaptor.forClass(TaskGraph.class);
        verify(turn).plan(graphCaptor.capture());
        ArgumentCaptor<String> focusCaptor = ArgumentCaptor.forClass(String.class);
        verify(turn).focus(focusCaptor.capture());
        assertThat(graphCaptor.getValue().getNodes()).hasSize(1);
        assertThat(graphCaptor.getValue().getNodes().getFirst().getSkillKey()).isEqualTo("cms.write");
        assertThat(focusCaptor.getValue()).isEqualTo(graphCaptor.getValue().getNodes().getFirst().getNodeId());
        assertThat(ctx.isFailed()).isFalse();
    }

    @Test
    void forcedSkill_capturedOnPlannedGraph() {
        TurnRequest request = new TurnRequest();
        request.setChannel("web");
        request.setSkillKey("cms.write");

        TurnScope turn = mock(TurnScope.class);
        TaskMetadata task = new TaskMetadata();
        task.setTaskId("task-1");
        when(turn.task()).thenReturn(Optional.of(task));
        when(turn.conversation()).thenReturn(new ConversationFence("c1", "u1", "task-1", 1L, null, 0L));

        TaskGraph[] captured = new TaskGraph[1];
        org.mockito.Mockito.doAnswer(invocation -> {
            captured[0] = invocation.getArgument(0);
            when(turn.graph()).thenReturn(captured[0]);
            return null;
        }).when(turn).plan(any(TaskGraph.class));

        TurnContext ctx = new TurnContext(request, turn);
        ctx.setDecision(TurnDecision.startFresh());
        ctx.setSkills(List.of(reactSkill("cms.read"), reactSkill("cms.write")));

        planner.plan(ctx);

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].getNodes()).hasSize(1);
        TaskNode node = captured[0].getNodes().getFirst();
        assertThat(node.getSkillKey()).isEqualTo("cms.write");
    }

    @Test
    void withoutSkillKey_usesSelector() {
        TurnRequest request = new TurnRequest();
        request.setChannel("web");
        request.setUserInput("查标签");

        TurnScope turn = mock(TurnScope.class);
        TaskMetadata task = new TaskMetadata();
        task.setTaskId("task-1");
        task.setGoalSummary("查标签");
        when(turn.task()).thenReturn(Optional.of(task));
        when(turn.conversation()).thenReturn(new ConversationFence("c1", "u1", "task-1", 1L, null, 0L));
        when(turn.graph()).thenReturn(new TaskGraph());

        SkillItem selected = new SkillItem("cms.read", "读");
        when(skillSelector.select(any(), any(), any())).thenReturn(List.of(selected));

        TurnContext ctx = new TurnContext(request, turn);
        ctx.setDecision(TurnDecision.startFresh());
        ctx.setSkills(List.of(reactSkill("cms.read"), reactSkill("cms.write")));

        planner.plan(ctx);

        verify(skillSelector).select(any(), any(), any());
        assertThat(ctx.isFailed()).isFalse();
    }

    @Test
    void continueTask_skipsPlanning_evenWithSkillKey() {
        TurnRequest request = new TurnRequest();
        request.setChannel("web");
        request.setSkillKey("cms.write");

        TurnScope turn = mock(TurnScope.class);
        TurnContext ctx = new TurnContext(request, turn);
        ctx.setDecision(TurnDecision.continueTask());
        ctx.setSkills(List.of(reactSkill("cms.write")));

        planner.plan(ctx);

        verify(skillSelector, never()).select(any(), any(), any());
        verify(turn, never()).plan(any());
    }

    private static Skill reactSkill(String key) {
        SkillContract contract = new SkillContract();
        contract.setMode(ExecutionMode.REACT);
        return new Skill(key, key, List.of(), "desc", null, contract, null);
    }
}
