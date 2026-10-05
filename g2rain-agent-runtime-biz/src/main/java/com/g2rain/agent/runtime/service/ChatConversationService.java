package com.g2rain.agent.runtime.service;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.agent.runtime.dto.ChatConversationDto;
import com.g2rain.agent.runtime.dto.ChatConversationSelectDto;
import com.g2rain.agent.runtime.vo.ChatConversationVo;

import java.util.List;

/**
 * 聊天会话表服务接口
 * 表名: chat_conversation
 *
 * @author G2rain Generator
 */
public interface ChatConversationService {

    /**
     * 根据条件查询列表
     *
     * @param selectDto 查询条件 DTO
     * @return VO 对象列表
     */
    List<ChatConversationVo> selectList(ChatConversationSelectDto selectDto);

    /**
     * 根据条件分页查询
     *
     * @param selectDto 查询条件DTO（包含分页参数）
     * @return 分页 VO 数据
     */
    PageData<ChatConversationVo> selectPage(PageSelectListDto<ChatConversationSelectDto> selectDto);

    /**
     * 新增或更新数据
     *
     * @param dto 数据传输对象
     * @return 操作结果（影响行数）
     */
    Long save(ChatConversationDto dto);

    /**
     * 轮末 CAS：推进 state_version，并写 session_shared / active_task_id。
     *
     * @param conversationId 会话 id
     * @param stateVersion   本轮栅栏版本
     * @param sessionShared  会话共享 JSON；可空
     * @param activeTaskId   活跃任务；终态可空
     * @return 影响行数；0 表示冲突或行不存在
     */
    int advanceStateVersion(String conversationId, long stateVersion, String sessionShared, String activeTaskId);

    /**
     * 根据 ID 删除数据
     *
     * @param id 主键 ID
     * @return 操作结果（影响行数）
     */
    int delete(Long id);
}