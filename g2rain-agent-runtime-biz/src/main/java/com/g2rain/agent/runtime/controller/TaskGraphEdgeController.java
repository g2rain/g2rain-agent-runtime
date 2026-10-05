package com.g2rain.agent.runtime.controller;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.api.TaskGraphEdgeApi;
import com.g2rain.agent.runtime.dto.TaskGraphEdgeDto;
import com.g2rain.agent.runtime.dto.TaskGraphEdgeSelectDto;
import com.g2rain.agent.runtime.service.TaskGraphEdgeService;
import com.g2rain.agent.runtime.vo.TaskGraphEdgeVo;
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
 * 任务图依赖边表控制器
 * 表名: task_graph_edge
 *
 * @author G2rain Generator
 */
@RestController
@RequestMapping("/task_graph_edge")
public class TaskGraphEdgeController implements TaskGraphEdgeApi {

    @Resource(name = "taskGraphEdgeServiceImpl")
    private TaskGraphEdgeService taskGraphEdgeService;

    @Override
    public Result<List<TaskGraphEdgeVo>> selectList(TaskGraphEdgeSelectDto selectDto) {
        return Result.success(taskGraphEdgeService.selectList(selectDto));
    }

    @Override
    public Result<PageData<TaskGraphEdgeVo>> selectPage(PageSelectListDto<TaskGraphEdgeSelectDto> selectDto) {
        return Result.successPage(taskGraphEdgeService.selectPage(selectDto));
    }

    @PostMapping("/save")
    @Operation(summary = "新增或更新任务图依赖边表信息", description = "新增或更新任务图依赖边表基础信息")
    public Result<Long> save(@RequestBody TaskGraphEdgeDto dto) {
        return Result.success(taskGraphEdgeService.save(dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除任务图依赖边表记录", description = "根据主键删除任务图依赖边表记录")
    public Result<Integer> delete(@Parameter(description = "任务图依赖边表标识") @PathVariable Long id) {
        return Result.success(taskGraphEdgeService.delete(id));
    }
}