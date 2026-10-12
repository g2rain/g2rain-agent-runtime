package com.g2rain.agent.runtime.dto;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.g2rain.common.model.BaseSelectListDto;
import io.swagger.v3.oas.annotations.media.Schema;


/**
 * 聊天消息表查询入参DTO
 * 用于ChatMessageDao.selectList方法的条件筛选
 * 表名: chat_message
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "聊天消息表查询入参 DTO")
public class ChatMessageSelectDto extends BaseSelectListDto {

    /**
     * 机构标识
     */
    @Schema(description = "机构标识")
    private Long organId;

    /**
     * 所属会话
     */
    @Schema(description = "所属会话")
    private String conversationId;

    /**
     * 关联任务；可空
     */
    @Schema(description = "关联任务；可空")
    private String taskId;

    /**
     * 中间文字所属节点
     */
    @Schema(description = "中间文字所属节点")
    private String nodeId;

    /**
     * 中间文字所属 ReAct 轮次
     */
    @Schema(description = "中间文字所属 ReAct 轮次")
    private Integer round;

    /**
     * 消息业务标识
     */
    @Schema(description = "消息业务标识")
    private String messageId;

    /**
     * 来源渠道/客户端应用
     */
    @Schema(description = "来源渠道/客户端应用")
    private String channel;

    /**
     * 本轮选用的模型名
     */
    @Schema(description = "本轮选用的模型名")
    private String model;

    /**
     * 角色[USER, AGENT]
     */
    @Schema(description = "角色[USER, AGENT]")
    private String role;

    /**
     * 消息类型[USER_TEXT, AGENT_REPLY]
     */
    @Schema(description = "消息类型[USER_TEXT, AGENT_REPLY]")
    private String messageType;

    /**
     * 本条消息追踪号
     */
    @Schema(description = "本条消息追踪号")
    private String traceId;

    /**
     * 业务发言时间 epoch ms
     */
    @Schema(description = "业务发言时间 epoch ms")
    private Long createdAtMs;

    /**
     * 消息内容
     */
    @Schema(description = "消息内容")
    private String content;
}