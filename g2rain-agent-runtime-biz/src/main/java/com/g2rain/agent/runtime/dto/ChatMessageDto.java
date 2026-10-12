package com.g2rain.agent.runtime.dto;

import com.g2rain.common.model.BaseDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.g2rain.common.validation.CreateGroup;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 聊天消息表查询DTO
 * 表名: chat_message
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "聊天消息表 DTO")
public class ChatMessageDto extends BaseDto {

    /**
     * 机构标识
     */
    @NotNull(groups = CreateGroup.class)
    @Schema(description = "机构标识（新增必填）")
    private Long organId;

    /**
     * 所属会话
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "所属会话（新增必填）", maxLength = 64)
    private String conversationId;

    /**
     * 关联任务；可空
     */
    @Size(max = 64)
    @Schema(description = "关联任务；可空", maxLength = 64)
    private String taskId;

    /**
     * 中间文字所属节点
     */
    @Size(max = 64)
    @Schema(description = "中间文字所属节点", maxLength = 64)
    private String nodeId;

    /**
     * 中间文字所属 ReAct 轮次
     */
    @Schema(description = "中间文字所属 ReAct 轮次")
    private Integer round;

    /**
     * 消息业务标识
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "消息业务标识（新增必填）", maxLength = 64)
    private String messageId;

    /**
     * 来源渠道/客户端应用
     */
    @Size(max = 32)
    @Schema(description = "来源渠道/客户端应用", maxLength = 32)
    private String channel;

    /**
     * 本轮选用的模型名
     */
    @Size(max = 128)
    @Schema(description = "本轮选用的模型名", maxLength = 128)
    private String model;

    /**
     * 角色[USER, AGENT]
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 32)
    @Schema(description = "角色[USER, AGENT]（新增必填）", maxLength = 32)
    private String role;

    /**
     * 消息类型[USER_TEXT, AGENT_REPLY]
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 32)
    @Schema(description = "消息类型[USER_TEXT, AGENT_REPLY]（新增必填）", maxLength = 32)
    private String messageType;

    /**
     * 本条消息追踪号
     */
    @Size(max = 64)
    @Schema(description = "本条消息追踪号", maxLength = 64)
    private String traceId;

    /**
     * 业务发言时间 epoch ms
     */
    @NotNull(groups = CreateGroup.class)
    @Schema(description = "业务发言时间 epoch ms（新增必填）")
    private Long createdAtMs;

    /**
     * 消息内容
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 16777215)
    @Schema(description = "消息内容（新增必填）", maxLength = 16777215)
    private String content;
}
