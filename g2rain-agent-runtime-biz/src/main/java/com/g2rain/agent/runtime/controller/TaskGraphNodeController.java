package com.g2rain.agent.runtime.controller;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.api.TaskGraphNodeApi;
import com.g2rain.agent.runtime.dto.TaskGraphNodeDto;
import com.g2rain.agent.runtime.dto.TaskGraphNodeSelectDto;
import com.g2rain.agent.runtime.service.TaskGraphNodeService;
import com.g2rain.agent.runtime.vo.TaskGraphNodeVo;
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
 * 任务图步骤表控制器
 * 表名: task_graph_node
 *
 * @author G2rain Generator
 */
@RestController
@RequestMapping("/task_graph_node")
public class TaskGraphNodeController implements TaskGraphNodeApi {

    @Resource(name = "taskGraphNodeServiceImpl")
    private TaskGraphNodeService taskGraphNodeService;

    @Override
    public Result<List<TaskGraphNodeVo>> selectList(TaskGraphNodeSelectDto selectDto) {
        return Result.success(taskGraphNodeService.selectList(selectDto));
    }

    @Override
    public Result<PageData<TaskGraphNodeVo>> selectPage(PageSelectListDto<TaskGraphNodeSelectDto> selectDto) {
        return Result.successPage(taskGraphNodeService.selectPage(selectDto));
    }

    @PostMapping("/save")
    @Operation(summary = "新增或更新任务图步骤表信息", description = "新增或更新任务图步骤表基础信息")
    public Result<Long> save(@RequestBody TaskGraphNodeDto dto) {
        return Result.success(taskGraphNodeService.save(dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除任务图步骤表记录", description = "根据主键删除任务图步骤表记录")
    public Result<Integer> delete(@Parameter(description = "任务图步骤表标识") @PathVariable Long id) {
        return Result.success(taskGraphNodeService.delete(id));
    }
}