package com.g2rain.agent.runtime.service;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.agent.runtime.dto.ToolCallRecordDto;
import com.g2rain.agent.runtime.dto.ToolCallRecordSelectDto;
import com.g2rain.agent.runtime.vo.ToolCallRecordVo;

import java.util.List;

/**
 * 工具调用记录表服务接口
 * 表名: tool_call_record
 *
 * @author G2rain Generator
 */
public interface ToolCallRecordService {

    /**
     * 根据条件查询列表
     *
     * @param selectDto 查询条件 DTO
     * @return VO 对象列表
     */
    List<ToolCallRecordVo> selectList(ToolCallRecordSelectDto selectDto);

    /**
     * 根据条件分页查询
     *
     * @param selectDto 查询条件DTO（包含分页参数）
     * @return 分页 VO 数据
     */
    PageData<ToolCallRecordVo> selectPage(PageSelectListDto<ToolCallRecordSelectDto> selectDto);

    /**
     * 新增或更新数据
     *
     * @param dto 数据传输对象
     * @return 操作结果（影响行数）
     */
    Long save(ToolCallRecordDto dto);

    /**
     * 根据 ID 删除数据
     *
     * @param id 主键 ID
     * @return 操作结果（影响行数）
     */
    int delete(Long id);
}