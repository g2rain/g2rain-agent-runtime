package com.g2rain.agent.runtime.controller;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.api.TaskResumeRecordApi;
import com.g2rain.agent.runtime.dto.TaskResumeRecordDto;
import com.g2rain.agent.runtime.dto.TaskResumeRecordSelectDto;
import com.g2rain.agent.runtime.service.TaskResumeRecordService;
import com.g2rain.agent.runtime.vo.TaskResumeRecordVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务恢复动作审计表控制器
 * 表名: task_resume_record
 *
 * @author G2rain Generator
 */
@RestController
@RequestMapping("/task_resume_record")
public class TaskResumeRecordController implements TaskResumeRecordApi {

    @Resource(name = "taskResumeRecordServiceImpl")
    private TaskResumeRecordService taskResumeRecordService;

    @Override
    public Result<List<TaskResumeRecordVo>> selectList(TaskResumeRecordSelectDto selectDto) {
        return Result.success(taskResumeRecordService.selectList(selectDto));
    }

    @Override
    public Result<PageData<TaskResumeRecordVo>> selectPage(PageSelectListDto<TaskResumeRecordSelectDto> selectDto) {
        return Result.successPage(taskResumeRecordService.selectPage(selectDto));
    }

    @PostMapping("/save")
    @Operation(summary = "新增或更新任务恢复动作审计表信息", description = "新增或更新任务恢复动作审计表基础信息")
    public Result<Long> save(@RequestBody TaskResumeRecordDto dto) {
        return Result.success(taskResumeRecordService.save(dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除任务恢复动作审计表记录", description = "根据主键删除任务恢复动作审计表记录")
    public Result<Integer> delete(@Parameter(description = "任务恢复动作审计表标识") @PathVariable Long id) {
        return Result.success(taskResumeRecordService.delete(id));
    }
}