package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.HaltReason;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.state.repo.CallRepository;
import com.g2rain.agent.runtime.engine.state.repo.ConversationRepository;
import com.g2rain.agent.runtime.engine.state.repo.GraphRepository;
import com.g2rain.agent.runtime.engine.state.repo.ProgressRepository;
import com.g2rain.agent.runtime.engine.state.repo.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RuntimeStatePersistence}：persistShell（U7～U9）与 persistTurn（W6）。
 *
 * @author alpha
 * @since 2026/11/22
 */
class RuntimeStatePersistenceTest {

    private static final String CONV = "c1";

    private static final String TASK = "t1";

    private ConversationRepository conversations;

    private TaskRepository tasks;

    private GraphRepository graphs;

    private RuntimeStatePersistence persistence;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationRepository.class);
        tasks = mock(TaskRepository.class);
        graphs = mock(GraphRepository.class);
        persistence = new RuntimeStatePersistence(conversations, tasks, graphs, mock(CallRepository.class),
            mock(ProgressRepository.class));
    }

    /** U7：新任务 → insertIfAbsent true 后 bindTask，再 insertMissing、bindActiveTask。 */
    @Test
    void u7_persistShell_newTask_bindsSourceMessage() {
        when(tasks.insertIfAbsent(any(), anyMap(), isNull())).thenReturn(true);
        DefaultTurnScope scope = turn(true);

        persistence.persistShell(scope);

        InOrder order = inOrder(tasks, conversations, graphs);
        order.verify(tasks).insertIfAbsent(any(), anyMap(), isNull());
        order.verify(conversations).bindTask("msg-1", TASK);
        order.verify(graphs).insertMissing(eq(TASK), any(), anyMap());
        order.verify(conversations).bindActiveTask(CONV, TASK);
    }

    /** U8：任务已存在 → 不 bindTask，仍 insertMissing / bindActiveTask。 */
    @Test
    void u8_persistShell_existingTask_skipsBindTask() {
        when(tasks.insertIfAbsent(any(), anyMap(), isNull())).thenReturn(false);
        DefaultTurnScope scope = turn(true);

        persistence.persistShell(scope);

        verify(conversations, never()).bindTask(any(), any());
        verify(graphs).insertMissing(eq(TASK), any(), anyMap());
        verify(conversations).bindActiveTask(CONV, TASK);
    }

    /** U9：无活动任务 → 不调仓储。 */
    @Test
    void u9_persistShell_noTask_skipsAll() {
        DefaultTurnScope scope = turn(false, TaskStatus.RUNNING);

        persistence.persistShell(scope);

        verify(tasks, never()).insertIfAbsent(any(), anyMap(), any());
        verify(graphs, never()).insertMissing(any(), any(), anyMap());
        verify(conversations, never()).bindActiveTask(any(), any());
        verify(conversations, never()).bindTask(any(), any());
    }

    /** W6：当前任务已结束 → save 的记录与返回 Map 是同一对象。 */
    @Test
    void w6_persistTurn_terminal_sameRecordObject() {
        when(conversations.advanceVersion(any(), anyLong(), any(), any())).thenReturn(1);
        when(tasks.save(any(), anyMap(), any(), anyLong(), any())).thenReturn(false);
        DefaultTurnScope scope = turn(true, TaskStatus.SUCCEEDED);

        Map<String, TaskRecord> written = persistence.persistTurn(scope);

        ArgumentCaptor<TaskRecord> captor = ArgumentCaptor.forClass(TaskRecord.class);
        verify(tasks).save(any(), anyMap(), any(), anyLong(), captor.capture());
        assertThat(written).containsKey(TASK);
        assertThat(written.get(TASK)).isSameAs(captor.getValue());
    }

    /** N1：换任务后 persist 按旧任务 id 消费断点。 */
    @Test
    void n1_persistTurn_replaceTask_consumesOldCheckpoint() {
        when(conversations.advanceVersion(any(), anyLong(), any(), any())).thenReturn(1);
        when(tasks.save(any(), anyMap(), any(), anyLong(), any())).thenReturn(false);
        DefaultTurnScope scope = turn(true);
        scope.suspend(checkpoint("n1"));
        scope.resume(ResumeAction.CANCEL, "msg-cancel");
        scope.close(TaskStatus.CANCELED, "cancelled");
        TaskMetadata next = new TaskMetadata();
        next.setTaskId("t2");
        next.setConversationId(CONV);
        next.setStatus(TaskStatus.RUNNING);
        next.setSourceMessageId("msg-2");
        scope.start(next, new Prior(List.of(), Map.of(), List.of()));

        persistence.persistTurn(scope);

        verify(tasks).consumeCheckpoint(eq(TASK), eq(ResumeAction.CANCEL), eq("msg-cancel"), anyLong());
    }

    /** N2：普通确认续跑后 persist 按当前任务消费断点。 */
    @Test
    void n2_persistTurn_confirm_consumesCurrentCheckpoint() {
        when(conversations.advanceVersion(any(), anyLong(), any(), any())).thenReturn(1);
        when(tasks.save(any(), anyMap(), any(), anyLong(), any())).thenReturn(false);
        DefaultTurnScope scope = turn(true);
        scope.suspend(checkpoint("n1"));
        scope.resume(ResumeAction.CONFIRM, "msg-confirm");

        persistence.persistTurn(scope);

        verify(tasks).consumeCheckpoint(eq(TASK), eq(ResumeAction.CONFIRM), eq("msg-confirm"), anyLong());
    }

    /** N3：本轮没有恢复 → 不调 consumeCheckpoint。 */
    @Test
    void n3_persistTurn_noResume_skipsConsumeCheckpoint() {
        when(conversations.advanceVersion(any(), anyLong(), any(), any())).thenReturn(1);
        when(tasks.save(any(), anyMap(), any(), anyLong(), any())).thenReturn(false);
        DefaultTurnScope scope = turn(true);

        persistence.persistTurn(scope);

        verify(tasks, never()).consumeCheckpoint(anyString(), any(), anyString(), anyLong());
    }

    private static TaskCheckpoint checkpoint(String nodeId) {
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setType(CheckpointType.APPROVAL);
        checkpoint.setHaltReason(HaltReason.APPROVAL);
        checkpoint.setNodeId(nodeId);
        checkpoint.setAllowedActions(List.of(ResumeAction.CONFIRM, ResumeAction.CANCEL));
        return checkpoint;
    }

    private static DefaultTurnScope turn(boolean withTask) {
        return turn(withTask, TaskStatus.RUNNING);
    }

    private static DefaultTurnScope turn(boolean withTask, TaskStatus status) {
        TaskMetadata meta = null;
        TaskGraph graph = new TaskGraph();
        if (withTask) {
            meta = new TaskMetadata();
            meta.setTaskId(TASK);
            meta.setConversationId(CONV);
            meta.setStatus(status);
            meta.setSourceMessageId("msg-1");
            TaskNode node = new TaskNode();
            node.setNodeId("n1");
            node.setStatus(NodeStatus.READY);
            graph.setNodes(List.of(node));
        }
        ConversationFence fence = new ConversationFence(CONV, "u1", withTask ? TASK : null, 1L, "trace",
            System.currentTimeMillis());
        return TurnScopes.create("1", "u1", "trace", fence, List.of(), List.of(), new HashMap<>(), meta, graph,
            null, new HashMap<>(), null, new HashMap<>(), 60);
    }
}
