package com.g2rain.agent.runtime.api;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.dto.ToolCallRecordSelectDto;
import com.g2rain.agent.runtime.vo.ToolCallRecordVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;


/**
 * 工具调用记录表API接口
 * 表名: tool_call_record
 *
 * @author G2rain Generator
 */
@Tag(name = "工具调用记录表", description = "工具调用记录表相关接口")
public interface ToolCallRecordApi {

    /**
     * 根据条件查询列表
     *
     * @param selectDto 查询条件 DTO
     * @return 数据列表
     */
    @GetMapping("/list")
    @Operation(summary = "查询工具调用记录表列表", description = "根据查询条件返回工具调用记录表列表")
    Result<List<ToolCallRecordVo>> selectList(ToolCallRecordSelectDto selectDto);

    /**
     * 根据条件分页查询
     *
     * @param selectDto 查询条件DTO（包含分页参数）
     * @return 分页数据
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询工具调用记录表列表", description = "分页查询工具调用记录表列表")
    Result<PageData<ToolCallRecordVo>> selectPage(PageSelectListDto<ToolCallRecordSelectDto> selectDto);
}