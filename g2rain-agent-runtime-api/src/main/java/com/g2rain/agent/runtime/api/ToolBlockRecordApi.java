package com.g2rain.agent.runtime.api;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.dto.ToolBlockRecordSelectDto;
import com.g2rain.agent.runtime.vo.ToolBlockRecordVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;


/**
 * 工具调用拦截记录表API接口
 * 表名: tool_block_record
 *
 * @author G2rain Generator
 */
@Tag(name = "工具调用拦截记录表", description = "工具调用拦截记录表相关接口")
public interface ToolBlockRecordApi {

    /**
     * 根据条件查询列表
     *
     * @param selectDto 查询条件 DTO
     * @return 数据列表
     */
    @GetMapping("/list")
    @Operation(summary = "查询工具调用拦截记录表列表", description = "根据查询条件返回工具调用拦截记录表列表")
    Result<List<ToolBlockRecordVo>> selectList(ToolBlockRecordSelectDto selectDto);

    /**
     * 根据条件分页查询
     *
     * @param selectDto 查询条件DTO（包含分页参数）
     * @return 分页数据
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询工具调用拦截记录表列表", description = "分页查询工具调用拦截记录表列表")
    Result<PageData<ToolBlockRecordVo>> selectPage(PageSelectListDto<ToolBlockRecordSelectDto> selectDto);
}