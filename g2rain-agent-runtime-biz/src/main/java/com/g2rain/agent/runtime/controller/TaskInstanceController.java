package com.g2rain.agent.runtime.controller;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.api.TaskInstanceApi;
import com.g2rain.agent.runtime.dto.TaskInstanceDto;
import com.g2rain.agent.runtime.dto.TaskInstanceSelectDto;
import com.g2rain.agent.runtime.service.TaskInstanceService;
import com.g2rain.agent.runtime.vo.TaskInstanceVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务实例表控制器
 * 表名: task_instance
 *
 * @author G2rain Generator
 */
@RestController
@RequestMapping("/task_instance")
public class TaskInstanceController implements TaskInstanceApi {

    @Resource(name = "taskInstanceServiceImpl")
    private TaskInstanceService taskInstanceService;

    @Override
    public Result<List<TaskInstanceVo>> selectList(TaskInstanceSelectDto selectDto) {
        return Result.success(taskInstanceService.selectSummaryList(selectDto));
    }

    @Override
    public Result<PageData<TaskInstanceVo>> selectPage(PageSelectListDto<TaskInstanceSelectDto> selectDto) {
        return Result.successPage(taskInstanceService.selectSummaryPage(selectDto));
    }

    @GetMapping("/by_task/{taskId}")
    @Operation(summary = "按任务标识查完整任务", description = "含 request/shared/record 等大字段")
    public Result<TaskInstanceVo> getByTaskId(@Parameter(description = "任务业务标识") @PathVariable String taskId) {
        return Result.success(taskInstanceService.getByTaskId(taskId));
    }

    @PostMapping("/save")
    @Operation(summary = "新增或更新任务实例表信息", description = "新增或更新任务实例表基础信息")
    public Result<Long> save(@RequestBody TaskInstanceDto dto) {
        return Result.success(taskInstanceService.save(dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除任务实例表记录", description = "根据主键删除任务实例表记录")
    public Result<Integer> delete(@Parameter(description = "任务实例表标识") @PathVariable Long id) {
        return Result.success(taskInstanceService.delete(id));
    }
}