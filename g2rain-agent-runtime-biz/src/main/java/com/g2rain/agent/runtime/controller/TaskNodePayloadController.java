package com.g2rain.agent.runtime.controller;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.api.TaskNodePayloadApi;
import com.g2rain.agent.runtime.dto.TaskNodePayloadDto;
import com.g2rain.agent.runtime.dto.TaskNodePayloadSelectDto;
import com.g2rain.agent.runtime.service.TaskNodePayloadService;
import com.g2rain.agent.runtime.vo.TaskNodePayloadVo;
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
 * 大报文溢出表控制器
 * 表名: task_node_payload
 *
 * @author G2rain Generator
 */
@RestController
@RequestMapping("/task_node_payload")
public class TaskNodePayloadController implements TaskNodePayloadApi {

    @Resource(name = "taskNodePayloadServiceImpl")
    private TaskNodePayloadService taskNodePayloadService;

    @Override
    public Result<List<TaskNodePayloadVo>> selectList(TaskNodePayloadSelectDto selectDto) {
        return Result.success(taskNodePayloadService.selectList(selectDto));
    }

    @Override
    public Result<PageData<TaskNodePayloadVo>> selectPage(PageSelectListDto<TaskNodePayloadSelectDto> selectDto) {
        return Result.successPage(taskNodePayloadService.selectPage(selectDto));
    }

    @PostMapping("/save")
    @Operation(summary = "新增或更新大报文溢出表信息", description = "新增或更新大报文溢出表基础信息")
    public Result<Long> save(@RequestBody TaskNodePayloadDto dto) {
        return Result.success(taskNodePayloadService.save(dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除大报文溢出表记录", description = "根据主键删除大报文溢出表记录")
    public Result<Integer> delete(@Parameter(description = "大报文溢出表标识") @PathVariable Long id) {
        return Result.success(taskNodePayloadService.delete(id));
    }
}