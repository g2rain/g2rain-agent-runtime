package com.g2rain.agent.runtime.dto;

import com.g2rain.common.model.BaseSelectListDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 聊天会话表查询入参DTO
 * 用于ChatConversationDao.selectList方法的条件筛选
 * 表名: chat_conversation
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "聊天会话表查询入参 DTO")
public class ChatConversationSelectDto extends BaseSelectListDto {

    /**
     * 机构标识
     */
    @Schema(description = "机构标识")
    private Long organId;

    /**
     * 会话唯一标识
     */
    @Schema(description = "会话唯一标识")
    private String conversationId;

    /**
     * 用户标识
     */
    @Schema(description = "用户标识")
    private Long userId;

    /**
     * 会话标题
     */
    @Schema(description = "会话标题")
    private String title;

    /**
     * 当前运行或挂起中的任务
     */
    @Schema(description = "当前运行或挂起中的任务")
    private String activeTaskId;

    /**
     * 本轮栅栏版本；新会话为 0，落库按此单调推进
     */
    @Schema(description = "本轮栅栏版本；新会话为 0，落库按此单调推进")
    private Long stateVersion;

    /**
     * 最近消息时间
     */
    @Schema(description = "最近消息时间")
    private LocalDateTime lastMessageTime;

    /**
     * 会话级共享值（带来源与过期时间）
     */
    @Schema(description = "会话级共享值（带来源与过期时间）")
    private String sessionShared;
}
