package com.g2rain.agent.runtime.controller;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.api.ToolCallRecordApi;
import com.g2rain.agent.runtime.dto.ToolCallRecordDto;
import com.g2rain.agent.runtime.dto.ToolCallRecordSelectDto;
import com.g2rain.agent.runtime.service.ToolCallRecordService;
import com.g2rain.agent.runtime.vo.ToolCallRecordVo;
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
 * 工具调用记录表控制器
 * 表名: tool_call_record
 *
 * @author G2rain Generator
 */
@RestController
@RequestMapping("/tool_call_record")
public class ToolCallRecordController implements ToolCallRecordApi {

    @Resource(name = "toolCallRecordServiceImpl")
    private ToolCallRecordService toolCallRecordService;

    @Override
    public Result<List<ToolCallRecordVo>> selectList(ToolCallRecordSelectDto selectDto) {
        return Result.success(toolCallRecordService.selectList(selectDto));
    }

    @Override
    public Result<PageData<ToolCallRecordVo>> selectPage(PageSelectListDto<ToolCallRecordSelectDto> selectDto) {
        return Result.successPage(toolCallRecordService.selectPage(selectDto));
    }

    @PostMapping("/save")
    @Operation(summary = "新增或更新工具调用记录表信息", description = "新增或更新工具调用记录表基础信息")
    public Result<Long> save(@RequestBody ToolCallRecordDto dto) {
        return Result.success(toolCallRecordService.save(dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除工具调用记录表记录", description = "根据主键删除工具调用记录表记录")
    public Result<Integer> delete(@Parameter(description = "工具调用记录表标识") @PathVariable Long id) {
        return Result.success(toolCallRecordService.delete(id));
    }
}