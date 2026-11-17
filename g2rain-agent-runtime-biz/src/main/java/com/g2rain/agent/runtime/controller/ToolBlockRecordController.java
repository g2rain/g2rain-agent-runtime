package com.g2rain.agent.runtime.controller;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.api.ToolBlockRecordApi;
import com.g2rain.agent.runtime.dto.ToolBlockRecordDto;
import com.g2rain.agent.runtime.dto.ToolBlockRecordSelectDto;
import com.g2rain.agent.runtime.service.ToolBlockRecordService;
import com.g2rain.agent.runtime.vo.ToolBlockRecordVo;
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
 * 工具调用拦截记录表控制器
 * 表名: tool_block_record
 *
 * @author G2rain Generator
 */
@RestController
@RequestMapping("/tool_block_record")
public class ToolBlockRecordController implements ToolBlockRecordApi {

    @Resource(name = "toolBlockRecordServiceImpl")
    private ToolBlockRecordService toolBlockRecordService;

    @Override
    public Result<List<ToolBlockRecordVo>> selectList(ToolBlockRecordSelectDto selectDto) {
        return Result.success(toolBlockRecordService.selectList(selectDto));
    }

    @Override
    public Result<PageData<ToolBlockRecordVo>> selectPage(PageSelectListDto<ToolBlockRecordSelectDto> selectDto) {
        return Result.successPage(toolBlockRecordService.selectPage(selectDto));
    }

    @PostMapping("/save")
    @Operation(summary = "新增或更新工具调用拦截记录表信息", description = "新增或更新工具调用拦截记录表基础信息")
    public Result<Long> save(@RequestBody ToolBlockRecordDto dto) {
        return Result.success(toolBlockRecordService.save(dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除工具调用拦截记录表记录", description = "根据主键删除工具调用拦截记录表记录")
    public Result<Integer> delete(@Parameter(description = "工具调用拦截记录表标识") @PathVariable Long id) {
        return Result.success(toolBlockRecordService.delete(id));
    }
}