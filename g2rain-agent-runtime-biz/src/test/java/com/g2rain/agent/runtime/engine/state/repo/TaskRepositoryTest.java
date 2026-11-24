package com.g2rain.agent.runtime.engine.state.repo;

import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.dto.TaskInstanceDto;
import com.g2rain.agent.runtime.service.TaskCheckpointService;
import com.g2rain.agent.runtime.service.TaskInstanceService;
import com.g2rain.agent.runtime.service.TaskResumeRecordService;
import com.g2rain.agent.runtime.vo.TaskInstanceVo;
import com.g2rain.common.json.JsonCodecFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TaskRepository} 任务记录回灌与 specPins 测试。
 *
 * @author alpha
 * @since 2026/11/16
 */
class TaskRepositoryTest {

    private static final String CONVERSATION_ID = "c1";

    private static final String TASK_ID = "t1";

    private static final int LIMIT = 3;

    private TaskInstanceService taskInstanceService;

    private TaskRepository repository;

    @BeforeEach
    void setUp() {
        taskInstanceService = mock(TaskInstanceService.class);
        repository = new TaskRepository(taskInstanceService, mock(TaskCheckpointService.class),
            mock(TaskResumeRecordService.class));
    }

    @Test
    void loadRecords_shouldSkipRowsWithoutRecordAndReturnChronologicalOrder() {
        when(taskInstanceService.selectLatest(CONVERSATION_ID, LIMIT))
            .thenReturn(List.of(row("t3"), row("t2"), new TaskInstanceVo()));

        List<TaskRecord> records = repository.loadRecords(CONVERSATION_ID, LIMIT);

        assertEquals(List.of("t2", "t3"), records.stream().map(TaskRecord::taskId).toList());
    }

    @Test
    void loadMetadata_shouldRoundTripSpecPins() {
        TaskInstanceVo vo = new TaskInstanceVo();
        vo.setTaskId(TASK_ID);
        vo.setConversationId(CONVERSATION_ID);
        vo.setStatus(TaskStatus.RUNNING.name());
        vo.setSpecPins("{\"cms.article\":\"pin-1\"}");
        when(taskInstanceService.selectList(any())).thenReturn(List.of(vo));

        TaskMetadata metadata = repository.loadMetadata(TASK_ID);

        assertEquals(Map.of("cms.article", "pin-1"), metadata.getSpecPins());
    }

    @Test
    void save_emptySpecPins_shouldPersistNull() {
        when(taskInstanceService.selectList(any())).thenReturn(List.of());
        TaskMetadata metadata = metadata();

        repository.save(metadata, Map.of(), null, 1L, null);

        ArgumentCaptor<TaskInstanceDto> captor = ArgumentCaptor.forClass(TaskInstanceDto.class);
        verify(taskInstanceService).save(captor.capture());
        assertNull(captor.getValue().getSpecPins());
    }

    @Test
    void save_withSpecPins_shouldPersistJson() {
        when(taskInstanceService.selectList(any())).thenReturn(List.of());
        TaskMetadata metadata = metadata();
        metadata.getSpecPins().put("cms.article", "pin-1");

        repository.save(metadata, Map.of(), null, 1L, null);

        ArgumentCaptor<TaskInstanceDto> captor = ArgumentCaptor.forClass(TaskInstanceDto.class);
        verify(taskInstanceService).save(captor.capture());
        assertEquals("{\"cms.article\":\"pin-1\"}", captor.getValue().getSpecPins());
    }

    /** 固定：已有行走 CAS，不 insert。 */
    @Test
    void save_existingRow_advancesStateVersion() {
        TaskInstanceVo existing = new TaskInstanceVo();
        existing.setId(9L);
        existing.setTaskId(TASK_ID);
        existing.setConversationId(CONVERSATION_ID);
        existing.setStatus(TaskStatus.RUNNING.name());
        existing.setStateVersion(0L);
        when(taskInstanceService.selectList(any())).thenReturn(List.of(existing));
        when(taskInstanceService.advanceStateVersion(any())).thenReturn(1);

        boolean created = repository.save(metadata(), Map.of(), null, 3L, null);

        assertThat(created).isFalse();
        verify(taskInstanceService, never()).save(any());
        ArgumentCaptor<TaskInstanceDto> captor = ArgumentCaptor.forClass(TaskInstanceDto.class);
        verify(taskInstanceService).advanceStateVersion(captor.capture());
        assertThat(captor.getValue().getStateVersion()).isEqualTo(3L);
    }

    /** R5：CAS 分支且 specPins 非空 → DTO 带 specPins。 */
    @Test
    void r5_save_casWithSpecPins_passesDto() {
        TaskInstanceVo existing = new TaskInstanceVo();
        existing.setId(9L);
        existing.setTaskId(TASK_ID);
        existing.setConversationId(CONVERSATION_ID);
        existing.setStatus(TaskStatus.RUNNING.name());
        existing.setStateVersion(0L);
        when(taskInstanceService.selectList(any())).thenReturn(List.of(existing));
        when(taskInstanceService.advanceStateVersion(any())).thenReturn(1);
        TaskMetadata metadata = metadata();
        metadata.getSpecPins().put("cms.article", "pin-1");

        repository.save(metadata, Map.of(), null, 3L, null);

        ArgumentCaptor<TaskInstanceDto> captor = ArgumentCaptor.forClass(TaskInstanceDto.class);
        verify(taskInstanceService).advanceStateVersion(captor.capture());
        assertEquals("{\"cms.article\":\"pin-1\"}", captor.getValue().getSpecPins());
    }

    /** U1：库中没有任务 → 插入 stateVersion=0。 */
    @Test
    void u1_insertIfAbsent_missing_insertsVersionZero() {
        when(taskInstanceService.selectList(any())).thenReturn(List.of());
        TaskMetadata metadata = metadata();
        metadata.setStartedAt(1_700_000_000_000L);
        metadata.setTraceId("trace-open");
        metadata.setSourceMessageId("msg-1");

        boolean inserted = repository.insertIfAbsent(metadata, Map.of(), null);

        assertThat(inserted).isTrue();
        ArgumentCaptor<TaskInstanceDto> captor = ArgumentCaptor.forClass(TaskInstanceDto.class);
        verify(taskInstanceService).save(captor.capture());
        TaskInstanceDto dto = captor.getValue();
        assertThat(dto.getStateVersion()).isEqualTo(0L);
        assertThat(dto.getTraceId()).isEqualTo("trace-open");
        assertThat(dto.getSourceMessageId()).isEqualTo("msg-1");
        assertThat(dto.getStartedTime()).isNotNull();
        assertThat(dto.getTaskRecord()).isNull();
    }

    /** U2：库中已有任务 → 不写。 */
    @Test
    void u2_insertIfAbsent_existing_skipsWrite() {
        TaskInstanceVo existing = new TaskInstanceVo();
        existing.setTaskId(TASK_ID);
        existing.setStatus(TaskStatus.RUNNING.name());
        when(taskInstanceService.selectList(any())).thenReturn(List.of(existing));

        boolean inserted = repository.insertIfAbsent(metadata(), Map.of(), null);

        assertThat(inserted).isFalse();
        verify(taskInstanceService, never()).save(any());
    }

    /** U3：先 insertIfAbsent，再 save(V≥1) 走 CAS。 */
    @Test
    void u3_insertIfAbsentThenSave_casWithRoundVersion() {
        when(taskInstanceService.selectList(any())).thenReturn(List.of()).thenAnswer(invocation -> {
            TaskInstanceVo vo = new TaskInstanceVo();
            vo.setId(1L);
            vo.setTaskId(TASK_ID);
            vo.setConversationId(CONVERSATION_ID);
            vo.setStatus(TaskStatus.RUNNING.name());
            vo.setStateVersion(0L);
            return List.of(vo);
        });
        when(taskInstanceService.advanceStateVersion(any())).thenReturn(1);

        assertThat(repository.insertIfAbsent(metadata(), Map.of(), null)).isTrue();
        boolean created = repository.save(metadata(), Map.of(), null, 2L, null);

        assertThat(created).isFalse();
        ArgumentCaptor<TaskInstanceDto> captor = ArgumentCaptor.forClass(TaskInstanceDto.class);
        verify(taskInstanceService).advanceStateVersion(captor.capture());
        assertThat(captor.getValue().getStateVersion()).isEqualTo(2L);
    }

    private static TaskMetadata metadata() {
        TaskMetadata metadata = new TaskMetadata();
        metadata.setTaskId(TASK_ID);
        metadata.setConversationId(CONVERSATION_ID);
        metadata.setStatus(TaskStatus.RUNNING);
        return metadata;
    }

    private static TaskInstanceVo row(String taskId) {
        TaskRecord record = new TaskRecord(taskId, "1", null, TaskStatus.SUCCEEDED, null, null, null, null,
            List.of(), List.of(), 0L);
        TaskInstanceVo vo = new TaskInstanceVo();
        vo.setTaskRecord(JsonCodecFactory.instance().obj2str(record));
        return vo;
    }
}
