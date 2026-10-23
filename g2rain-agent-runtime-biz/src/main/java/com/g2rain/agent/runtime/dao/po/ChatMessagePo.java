package com.g2rain.agent.runtime.dao.po;

import com.g2rain.common.model.BasePo;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


/**
 * 聊天消息表返回Po
 * 关联表名: chat_message
 * 功能：封装实体数据，继承BasePo复用基础字段逻辑
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ChatMessagePo extends BasePo {

    /**
     * 机构标识
     */
    private Long organId;

    /**
     * 所属会话
     */
    private String conversationId;

    /**
     * 关联任务；可空
     */
    private String taskId;

    /**
     * 中间文字所属节点
     */
    private String nodeId;

    /**
     * 中间文字所属 ReAct 轮次
     */
    private Integer round;

    /**
     * 消息业务标识
     */
    private String messageId;

    /**
     * 来源渠道/客户端应用
     */
    private String channel;

    /**
     * 本轮选用的模型名
     */
    private String model;

    /**
     * 角色[USER, AGENT]
     */
    private String role;

    /**
     * 消息类型[USER_TEXT, AGENT_REPLY]
     */
    private String messageType;

    /**
     * 本条消息追踪号
     */
    private String traceId;

    /**
     * 业务发言时间 epoch ms
     */
    private Long createdAtMs;

    /**
     * 本轮耗时毫秒；仅最终回复
     */
    private Integer elapsedMs;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 删除标识[0:未删除, 1:已删除]
     */
    private Boolean deleteFlag;
}