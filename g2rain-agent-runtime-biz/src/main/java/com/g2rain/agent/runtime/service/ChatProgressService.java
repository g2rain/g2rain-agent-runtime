package com.g2rain.agent.runtime.service;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.agent.runtime.dto.ChatProgressDto;
import com.g2rain.agent.runtime.dto.ChatProgressSelectDto;
import com.g2rain.agent.runtime.vo.ChatProgressVo;

import java.util.List;

/**
 * 执行过程步骤表服务接口
 * 表名: chat_progress
 *
 * @author G2rain Generator
 */
public interface ChatProgressService {

    /**
     * 根据条件查询列表
     *
     * @param selectDto 查询条件 DTO
     * @return VO 对象列表
     */
    List<ChatProgressVo> selectList(ChatProgressSelectDto selectDto);

    /**
     * 根据条件分页查询
     *
     * @param selectDto 查询条件DTO（包含分页参数）
     * @return 分页 VO 数据
     */
    PageData<ChatProgressVo> selectPage(PageSelectListDto<ChatProgressSelectDto> selectDto);

    /**
     * 新增或更新数据
     *
     * @param dto 数据传输对象
     * @return 操作结果（影响行数）
     */
    Long save(ChatProgressDto dto);

    /**
     * 根据 ID 删除数据
     *
     * @param id 主键 ID
     * @return 操作结果（影响行数）
     */
    int delete(Long id);

    /**
     * 批量新增（本轮执行步骤轮末一次写入）
     *
     * @param list 数据传输对象列表
     * @return 影响行数
     */
    int saveBatch(List<ChatProgressDto> list);
}
