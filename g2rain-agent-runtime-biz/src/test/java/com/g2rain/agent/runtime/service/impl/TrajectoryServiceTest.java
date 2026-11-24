package com.g2rain.agent.runtime.service.impl;

import com.g2rain.agent.runtime.dao.TaskGraphNodeDao;
import com.g2rain.agent.runtime.dao.TaskInstanceDao;
import com.g2rain.agent.runtime.dao.ToolBlockRecordDao;
import com.g2rain.agent.runtime.dao.ToolCallRecordDao;
import com.g2rain.agent.runtime.dao.po.TaskGraphNodePo;
import com.g2rain.agent.runtime.dao.po.TaskInstancePo;
import com.g2rain.agent.runtime.dao.po.ToolBlockRecordPo;
import com.g2rain.agent.runtime.dao.po.ToolCallRecordPo;
import com.g2rain.agent.runtime.dto.TaskInstanceSelectDto;
import com.g2rain.agent.runtime.vo.TrajectoryVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TrajectoryServiceImpl} 轨迹组装验收。
 *
 * @author alpha
 * @since 2026/11/23
 */
class TrajectoryServiceTest {

    private TaskInstanceDao taskInstanceDao;
    private TaskGraphNodeDao taskGraphNodeDao;
    private ToolCallRecordDao toolCallRecordDao;
    private ToolBlockRecordDao toolBlockRecordDao;
    private TrajectoryServiceImpl service;

    @BeforeEach
    void setUp() {
        taskInstanceDao = mock(TaskInstanceDao.class);
        taskGraphNodeDao = mock(TaskGraphNodeDao.class);
        toolCallRecordDao = mock(ToolCallRecordDao.class);
        toolBlockRecordDao = mock(ToolBlockRecordDao.class);
        service = new TrajectoryServiceImpl();
        setField(service, "taskInstanceDao", taskInstanceDao);
        setField(service, "taskGraphNodeDao", taskGraphNodeDao);
        setField(service, "toolCallRecordDao", toolCallRecordDao);
        setField(service, "toolBlockRecordDao", toolBlockRecordDao);
    }

    @Test
    void emptyConversation_returnsEmptyStructure() {
        when(taskInstanceDao.selectSummaryList(any())).thenReturn(List.of());

        TrajectoryVo vo = service.conversation("missing");

        assertEquals("missing", vo.getConversationId());
        assertTrue(vo.getTasks().isEmpty());
        assertTrue(vo.getCalls().isEmpty());
        assertTrue(vo.getBlocks().isEmpty());
        verify(taskGraphNodeDao, never()).selectByTaskIds(anyCollection());
        verify(toolCallRecordDao, never()).selectByTaskIds(anyCollection());
        verify(toolBlockRecordDao, never()).selectByTaskIds(anyCollection());
    }

    @Test
    void blankConversationId_returnsEmptyWithoutQuery() {
        TrajectoryVo vo = service.conversation("  ");

        assertEquals("  ", vo.getConversationId());
        assertTrue(vo.getTasks().isEmpty());
        verify(taskInstanceDao, never()).selectSummaryList(any());
    }

    @Test
    void twoTasks_nodesCallsBlocks_sortedAndGrouped() {
        LocalDateTime t1 = LocalDateTime.of(2026, 11, 23, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 11, 23, 11, 0);
        TaskInstancePo taskA = task("task-a", 1L, "SUCCEEDED", "trace-a", t1, t1.plusMinutes(5));
        TaskInstancePo taskB = task("task-b", 2L, "SUSPENDED", "trace-b", t2, null);
        when(taskInstanceDao.selectSummaryList(any())).thenReturn(List.of(taskA, taskB));

        TaskGraphNodePo nodeB1 = node("task-b", "n-b1", "审批", "cms.write", "SUSPENDED", null);
        TaskGraphNodePo nodeA1 = node("task-a", "n-a1", "查询", "cms.read", "DONE", null);
        TaskGraphNodePo nodeA2 = node("task-a", "n-a2", "打标", "cms.write", "DONE", null);
        when(taskGraphNodeDao.selectByTaskIds(anyCollection())).thenReturn(List.of(nodeA1, nodeA2, nodeB1));

        ToolCallRecordPo callA = call("c-a", "task-a", "n-a1", 1, t1.plusSeconds(10));
        ToolCallRecordPo callB = call("c-b", "task-b", "n-b1", 1, t2.plusSeconds(10));
        when(toolCallRecordDao.selectByTaskIds(anyCollection())).thenReturn(List.of(callA, callB));

        ToolBlockRecordPo blockB = block("c-block", "task-b", "n-b1", t2.plusSeconds(5));
        when(toolBlockRecordDao.selectByTaskIds(anyCollection())).thenReturn(List.of(blockB));

        TrajectoryVo vo = service.conversation("conv-1");

        ArgumentCaptor<TaskInstanceSelectDto> queryCaptor = ArgumentCaptor.forClass(TaskInstanceSelectDto.class);
        verify(taskInstanceDao).selectSummaryList(queryCaptor.capture());
        assertEquals("conv-1", queryCaptor.getValue().getConversationId());
        assertEquals(false, queryCaptor.getValue().isDefaultIdDescSortEnabled());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> taskIdsCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(taskGraphNodeDao).selectByTaskIds(taskIdsCaptor.capture());
        assertEquals(List.of("task-a", "task-b"), List.copyOf(taskIdsCaptor.getValue()));

        assertEquals(2, vo.getTasks().size());
        assertEquals("task-a", vo.getTasks().get(0).getTaskId());
        assertEquals("task-b", vo.getTasks().get(1).getTaskId());
        assertEquals(List.of("n-a1", "n-a2"),
            vo.getTasks().get(0).getNodes().stream().map(TrajectoryVo.Node::getNodeId).toList());
        assertEquals(List.of("n-b1"),
            vo.getTasks().get(1).getNodes().stream().map(TrajectoryVo.Node::getNodeId).toList());
        assertEquals(List.of("c-a", "c-b"),
            vo.getCalls().stream().map(TrajectoryVo.Call::getCallId).toList());
        assertEquals(1, vo.getBlocks().size());
        assertEquals("c-block", vo.getBlocks().getFirst().getCallId());
        assertEquals("WriteGate", vo.getBlocks().getFirst().getBlockedBy());
    }

    private static TaskInstancePo task(String taskId, Long agentId, String status, String traceId,
                                       LocalDateTime started, LocalDateTime finished) {
        TaskInstancePo po = new TaskInstancePo();
        po.setTaskId(taskId);
        po.setAgentId(agentId);
        po.setStatus(status);
        po.setTraceId(traceId);
        po.setStartedTime(started);
        po.setFinishedTime(finished);
        return po;
    }

    private static TaskGraphNodePo node(String taskId, String nodeId, String title, String skillKey,
                                        String status, String errorCode) {
        TaskGraphNodePo po = new TaskGraphNodePo();
        po.setTaskId(taskId);
        po.setNodeId(nodeId);
        po.setTitle(title);
        po.setSkillKey(skillKey);
        po.setStatus(status);
        po.setErrorCode(errorCode);
        return po;
    }

    private static ToolCallRecordPo call(String callId, String taskId, String nodeId, int round,
                                         LocalDateTime createTime) {
        ToolCallRecordPo po = new ToolCallRecordPo();
        po.setCallId(callId);
        po.setTaskId(taskId);
        po.setNodeId(nodeId);
        po.setRound(round);
        po.setToolName("query");
        po.setSideEffect("NONE");
        po.setRequest("{\"q\":1}");
        po.setCallStatus("SUCCESS");
        po.setBusinessOk((byte) 1);
        po.setEmptyResult((byte) 0);
        po.setDurationMs(12L);
        po.setTraceId("trace-" + callId);
        po.setCreateTime(createTime);
        return po;
    }

    private static ToolBlockRecordPo block(String callId, String taskId, String nodeId, LocalDateTime createTime) {
        ToolBlockRecordPo po = new ToolBlockRecordPo();
        po.setCallId(callId);
        po.setTaskId(taskId);
        po.setNodeId(nodeId);
        po.setRound(1);
        po.setSkillKey("cms.write");
        po.setToolName("save");
        po.setBlockedBy("WriteGate");
        po.setErrorCode("agent_runtime.approval");
        po.setReasonCode("APPROVAL_REQUIRED");
        po.setMessage("需确认");
        po.setArgs("{\"id\":1}");
        po.setTraceId("trace-block");
        po.setCreateTime(createTime);
        return po;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = TrajectoryServiceImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
