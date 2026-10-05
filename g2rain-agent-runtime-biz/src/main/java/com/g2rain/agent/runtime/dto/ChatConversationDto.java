package com.g2rain.agent.runtime.dto;

import com.g2rain.common.model.BaseDto;
import com.g2rain.common.validation.CreateGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 聊天会话表查询DTO
 * 表名: chat_conversation
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "聊天会话表 DTO")
public class ChatConversationDto extends BaseDto {

    /**
     * 机构标识
     */
    @NotNull(groups = CreateGroup.class)
    @Schema(description = "机构标识（新增必填）")
    private Long organId;

    /**
     * 会话唯一标识
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "会话唯一标识（新增必填）", maxLength = 64)
    private String conversationId;

    /**
     * 用户标识
     */
    @NotNull(groups = CreateGroup.class)
    @Schema(description = "用户标识（新增必填）")
    private Long userId;

    /**
     * 会话标题
     */
    @Size(max = 256)
    @Schema(description = "会话标题", maxLength = 256)
    private String title;

    /**
     * 当前运行或挂起中的任务
     */
    @Size(max = 64)
    @Schema(description = "当前运行或挂起中的任务", maxLength = 64)
    private String activeTaskId;

    /**
     * 本轮栅栏版本；新会话为 0，落库按此单调推进
     */
    @NotNull(groups = CreateGroup.class)
    @Schema(description = "本轮栅栏版本；新会话为 0，落库按此单调推进（新增必填）")
    private Long stateVersion;

    /**
     * 最近消息时间
     */
    @Schema(description = "最近消息时间")
    private LocalDateTime lastMessageTime;

    /**
     * 会话级共享值（带来源与过期时间）
     */
    @Size(max = 1073741824)
    @Schema(description = "会话级共享值（带来源与过期时间）", maxLength = 1073741824)
    private String sessionShared;
}
