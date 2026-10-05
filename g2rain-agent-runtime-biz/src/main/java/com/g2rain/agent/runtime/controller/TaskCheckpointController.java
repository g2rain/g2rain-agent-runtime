package com.g2rain.agent.runtime.controller;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.api.TaskCheckpointApi;
import com.g2rain.agent.runtime.dto.TaskCheckpointDto;
import com.g2rain.agent.runtime.dto.TaskCheckpointSelectDto;
import com.g2rain.agent.runtime.service.TaskCheckpointService;
import com.g2rain.agent.runtime.vo.TaskCheckpointVo;
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
 * 任务检查点表控制器
 * 表名: task_checkpoint
 *
 * @author G2rain Generator
 */
@RestController
@RequestMapping("/task_checkpoint")
public class TaskCheckpointController implements TaskCheckpointApi {

    @Resource(name = "taskCheckpointServiceImpl")
    private TaskCheckpointService taskCheckpointService;

    @Override
    public Result<List<TaskCheckpointVo>> selectList(TaskCheckpointSelectDto selectDto) {
        return Result.success(taskCheckpointService.selectList(selectDto));
    }

    @Override
    public Result<PageData<TaskCheckpointVo>> selectPage(PageSelectListDto<TaskCheckpointSelectDto> selectDto) {
        return Result.successPage(taskCheckpointService.selectPage(selectDto));
    }

    @PostMapping("/save")
    @Operation(summary = "新增或更新任务检查点表信息", description = "新增或更新任务检查点表基础信息")
    public Result<Long> save(@RequestBody TaskCheckpointDto dto) {
        return Result.success(taskCheckpointService.save(dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除任务检查点表记录", description = "根据主键删除任务检查点表记录")
    public Result<Integer> delete(@Parameter(description = "任务检查点表标识") @PathVariable Long id) {
        return Result.success(taskCheckpointService.delete(id));
    }
}