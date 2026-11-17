package com.g2rain.agent.runtime.service;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.agent.runtime.dto.ChatMessageDto;
import com.g2rain.agent.runtime.dto.ChatMessageSelectDto;
import com.g2rain.agent.runtime.vo.ChatMessageVo;

import java.util.List;

/**
 * 聊天消息表服务接口
 * 表名: chat_message
 *
 * @author G2rain Generator
 */
public interface ChatMessageService {

    /**
     * 根据条件查询列表
     *
     * @param selectDto 查询条件 DTO
     * @return VO 对象列表
     */
    List<ChatMessageVo> selectList(ChatMessageSelectDto selectDto);

    /**
     * 根据条件分页查询
     *
     * @param selectDto 查询条件DTO（包含分页参数）
     * @return 分页 VO 数据
     */
    PageData<ChatMessageVo> selectPage(PageSelectListDto<ChatMessageSelectDto> selectDto);

    /**
     * 查询会话最近的若干条消息，不统计总数
     *
     * @param conversationId 会话 id
     * @param limit          最多条数
     * @return VO 对象列表，按时间倒序（最新在前）
     */
    List<ChatMessageVo> selectLatest(String conversationId, int limit);

    /**
     * 新增或更新数据
     *
     * @param dto 数据传输对象
     * @return 操作结果（影响行数）
     */
    Long save(ChatMessageDto dto);

    /**
     * 根据 ID 删除数据
     *
     * @param id 主键 ID
     * @return 操作结果（影响行数）
     */
    int delete(Long id);
}