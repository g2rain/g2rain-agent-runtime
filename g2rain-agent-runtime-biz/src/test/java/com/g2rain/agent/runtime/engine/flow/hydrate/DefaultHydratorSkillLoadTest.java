package com.g2rain.agent.runtime.engine.flow.hydrate;

import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.TurnStatus;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.HaltReason;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultHydrator} 技能装载失败用例 H3～H8、H11。
 *
 * @author alpha
 * @since 2026/11/22
 */
class DefaultHydratorSkillLoadTest {

    private static final long AGENT_ID = 9L;

    private BundleLoader bundleLoader;

    private DefaultHydrator hydrator;

    @BeforeEach
    void setUp() {
        bundleLoader = mock(BundleLoader.class);
        hydrator = new DefaultHydrator(bundleLoader);
    }

    /** H3：图有 A、B，只装到 A → 本轮失败，任务不动。 */
    @Test
    void h3_hydrate_missingSkill_failsTurnKeepsTask() {
        when(bundleLoader.load(anyLong(), anyMap())).thenReturn(List.of(skill("A")));
        TaskMetadata task = task(TaskStatus.SUSPENDED);
        TaskCheckpoint checkpoint = checkpoint();
        TurnScope turn = turnWith(task, graph("A", "B"), checkpoint);
        TurnContext ctx = context(turn);

        hydrator.hydrate(ctx);

        assertThat(ctx.isFailed()).isTrue();
        assertThat(ctx.getResult()).isNotNull();
        assertThat(ctx.getResult().getStatus()).isEqualTo(TurnStatus.FAILED);
        assertThat(ctx.getResult().getErrorCode()).isEqualTo(RuntimeErrorCode.SKILL_UNAVAILABLE.code());
        verify(turn, never()).abort(any(), any());
        verify(turn, never()).fail(any(), any());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUSPENDED);
        assertThat(turn.checkpoint()).contains(checkpoint);
    }

    /** H4：图中技能全部装载成功 → 不失败。 */
    @Test
    void h4_hydrate_allSkillsPresent_ok() {
        when(bundleLoader.load(anyLong(), anyMap())).thenReturn(List.of(skill("A"), skill("B")));
        TurnContext ctx = context(turnWith(task(TaskStatus.RUNNING), graph("A", "B"), null));

        hydrator.hydrate(ctx);

        assertThat(ctx.isFailed()).isFalse();
        assertThat(ctx.getResult()).isNull();
    }

    /** H5：本轮新建任务（图为空），装载缺技能 → 不失败。 */
    @Test
    void h5_hydrate_emptyGraph_missingSkills_ok() {
        when(bundleLoader.load(anyLong(), anyMap())).thenReturn(List.of(skill("A")));
        TurnContext ctx = context(turnWith(task(TaskStatus.RUNNING), new TaskGraph(), null));

        hydrator.hydrate(ctx);

        assertThat(ctx.isFailed()).isFalse();
    }

    /** H6：bundleLoader 抛 DEPENDENCY_UNAVAILABLE → 本轮失败，任务不变。 */
    @Test
    void h6_hydrate_dependencyUnavailable_failsTurn() {
        when(bundleLoader.load(anyLong(), anyMap()))
            .thenThrow(new BusinessException(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, "agent-studio"));
        TaskMetadata task = task(TaskStatus.RUNNING);
        TurnScope turn = turnWith(task, graph("A"), null);
        TurnContext ctx = context(turn);

        hydrator.hydrate(ctx);

        assertThat(ctx.isFailed()).isTrue();
        assertThat(ctx.getResult().getErrorCode()).isEqualTo(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE.code());
        verify(turn, never()).abort(any(), any());
        verify(turn, never()).fail(any(), any());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
    }

    /** H7：bundleLoader 抛其他异常 → 照常抛出。 */
    @Test
    void h7_hydrate_otherBusinessException_propagates() {
        when(bundleLoader.load(anyLong(), anyMap()))
            .thenThrow(new BusinessException(RuntimeErrorCode.SKILL_NOT_FOUND, "x"));
        TurnContext ctx = context(turnWith(task(TaskStatus.RUNNING), graph("A"), null));

        assertThatThrownBy(() -> hydrator.hydrate(ctx))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(RuntimeErrorCode.SKILL_NOT_FOUND.code());
        assertThat(ctx.isFailed()).isFalse();
    }

    /** H8：节点 skillKey 为空 → 跳过，不算缺失。 */
    @Test
    void h8_hydrate_blankSkillKey_skipped() {
        when(bundleLoader.load(anyLong(), anyMap())).thenReturn(List.of(skill("A")));
        TaskGraph graph = new TaskGraph();
        TaskNode blank = new TaskNode();
        blank.setNodeId("n0");
        blank.setSkillKey("  ");
        TaskNode ok = new TaskNode();
        ok.setNodeId("n1");
        ok.setSkillKey("A");
        graph.setNodes(List.of(blank, ok));
        TurnContext ctx = context(turnWith(task(TaskStatus.RUNNING), graph, null));

        hydrator.hydrate(ctx);

        assertThat(ctx.isFailed()).isFalse();
    }

    /** H11：新建任务（图为空），bundleLoader 因来源不可用抛 DEPENDENCY_UNAVAILABLE → 本轮失败。 */
    @Test
    void h11_hydrate_emptyGraph_dependencyUnavailable_failsTurn() {
        when(bundleLoader.load(anyLong(), anyMap()))
            .thenThrow(new BusinessException(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, "ONTOLOGY"));
        TaskMetadata task = task(TaskStatus.RUNNING);
        TurnScope turn = turnWith(task, new TaskGraph(), null);
        TurnContext ctx = context(turn);

        hydrator.hydrate(ctx);

        assertThat(ctx.isFailed()).isTrue();
        assertThat(ctx.getResult().getErrorCode()).isEqualTo(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE.code());
        verify(turn, never()).abort(any(), any());
        verify(turn, never()).fail(any(), any());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
    }

    private static TurnContext context(TurnScope turn) {
        TurnContext context = new TurnContext(mock(TurnRequest.class), turn);
        AgentSelection agent = new AgentSelection();
        agent.setAgentId(AGENT_ID);
        context.setAgentSelection(agent);
        return context;
    }

    private static TurnScope turnWith(TaskMetadata task, TaskGraph graph, TaskCheckpoint checkpoint) {
        TurnScope turn = mock(TurnScope.class);
        when(turn.task()).thenReturn(Optional.of(task));
        when(turn.graph()).thenReturn(graph);
        when(turn.checkpoint()).thenReturn(Optional.ofNullable(checkpoint));
        return turn;
    }

    private static TaskMetadata task(TaskStatus status) {
        TaskMetadata task = new TaskMetadata();
        task.setTaskId("t1");
        task.setStatus(status);
        return task;
    }

    private static TaskCheckpoint checkpoint() {
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setType(CheckpointType.APPROVAL);
        checkpoint.setHaltReason(HaltReason.APPROVAL);
        checkpoint.setNodeId("n1");
        return checkpoint;
    }

    private static TaskGraph graph(String... skillKeys) {
        TaskGraph graph = new TaskGraph();
        java.util.ArrayList<TaskNode> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < skillKeys.length; i++) {
            TaskNode node = new TaskNode();
            node.setNodeId("n" + i);
            node.setSkillKey(skillKeys[i]);
            nodes.add(node);
        }
        graph.setNodes(nodes);
        return graph;
    }

    private static Skill skill(String key) {
        return new Skill(key, key, List.of(), null, null, new SkillContract(), null);
    }
}
