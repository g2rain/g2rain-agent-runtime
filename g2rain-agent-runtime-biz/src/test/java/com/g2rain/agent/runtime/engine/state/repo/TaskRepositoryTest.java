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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TaskRepository} 任务记录回灌与 skillVersions 测试。
 *
 * @author alpha
 * @since 2026/11/16
 */
class TaskRepositoryTest {

    private static final String CONVERSATION_ID = "c1";

    private static final String TASK_ID = "t1";

    private static final int LIMIT = 3;

    @Test
    void loadRecords_shouldSkipRowsWithoutRecordAndReturnChronologicalOrder() {
        TaskInstanceService taskInstanceService = mock(TaskInstanceService.class);
        when(taskInstanceService.selectLatest(CONVERSATION_ID, LIMIT))
            .thenReturn(List.of(row("t3"), row("t2"), new TaskInstanceVo()));
        TaskRepository repository = new TaskRepository(taskInstanceService, mock(TaskCheckpointService.class),
            mock(TaskResumeRecordService.class));

        List<TaskRecord> records = repository.loadRecords(CONVERSATION_ID, LIMIT);

        assertEquals(List.of("t2", "t3"), records.stream().map(TaskRecord::taskId).toList());
    }

    @Test
    void loadMetadata_shouldRoundTripSkillVersions() {
        TaskInstanceService taskInstanceService = mock(TaskInstanceService.class);
        TaskInstanceVo vo = new TaskInstanceVo();
        vo.setTaskId(TASK_ID);
        vo.setConversationId(CONVERSATION_ID);
        vo.setStatus(TaskStatus.RUNNING.name());
        vo.setSkillVersions("{\"cms.article\":\"pin-1\"}");
        when(taskInstanceService.selectList(any())).thenReturn(List.of(vo));
        TaskRepository repository = new TaskRepository(taskInstanceService, mock(TaskCheckpointService.class),
            mock(TaskResumeRecordService.class));

        TaskMetadata metadata = repository.loadMetadata(TASK_ID);

        assertEquals(Map.of("cms.article", "pin-1"), metadata.getSkillVersions());
    }

    @Test
    void save_emptySkillVersions_shouldPersistNull() {
        TaskInstanceService taskInstanceService = mock(TaskInstanceService.class);
        when(taskInstanceService.selectList(any())).thenReturn(List.of());
        TaskRepository repository = new TaskRepository(taskInstanceService, mock(TaskCheckpointService.class),
            mock(TaskResumeRecordService.class));
        TaskMetadata metadata = new TaskMetadata();
        metadata.setTaskId(TASK_ID);
        metadata.setConversationId(CONVERSATION_ID);
        metadata.setStatus(TaskStatus.RUNNING);

        repository.save(metadata, Map.of(), null, 1L, null);

        ArgumentCaptor<TaskInstanceDto> captor = ArgumentCaptor.forClass(TaskInstanceDto.class);
        verify(taskInstanceService).save(captor.capture());
        assertNull(captor.getValue().getSkillVersions());
    }

    @Test
    void save_withSkillVersions_shouldPersistJson() {
        TaskInstanceService taskInstanceService = mock(TaskInstanceService.class);
        when(taskInstanceService.selectList(any())).thenReturn(List.of());
        TaskRepository repository = new TaskRepository(taskInstanceService, mock(TaskCheckpointService.class),
            mock(TaskResumeRecordService.class));
        TaskMetadata metadata = new TaskMetadata();
        metadata.setTaskId(TASK_ID);
        metadata.setConversationId(CONVERSATION_ID);
        metadata.setStatus(TaskStatus.RUNNING);
        metadata.getSkillVersions().put("cms.article", "pin-1");

        repository.save(metadata, Map.of(), null, 1L, null);

        ArgumentCaptor<TaskInstanceDto> captor = ArgumentCaptor.forClass(TaskInstanceDto.class);
        verify(taskInstanceService).save(captor.capture());
        assertEquals("{\"cms.article\":\"pin-1\"}", captor.getValue().getSkillVersions());
    }

    private static TaskInstanceVo row(String taskId) {
        TaskRecord record = new TaskRecord(taskId, "1", null, TaskStatus.SUCCEEDED, null, null, null, null,
            List.of(), List.of(), 0L);
        TaskInstanceVo vo = new TaskInstanceVo();
        vo.setTaskRecord(JsonCodecFactory.instance().obj2str(record));
        return vo;
    }
}
