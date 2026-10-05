package com.g2rain.agent.runtime.dao.po;

import com.g2rain.common.model.BasePo;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 聊天会话表返回Po
 * 关联表名: chat_conversation
 * 功能：封装实体数据，继承BasePo复用基础字段逻辑
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ChatConversationPo extends BasePo {

    /**
     * 机构标识
     */
    private Long organId;

    /**
     * 会话唯一标识
     */
    private String conversationId;

    /**
     * 用户标识
     */
    private Long userId;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 当前运行或挂起中的任务
     */
    private String activeTaskId;

    /**
     * 本轮栅栏版本；新会话为 0，落库按此单调推进
     */
    private Long stateVersion;

    /**
     * 最近消息时间
     */
    private LocalDateTime lastMessageTime;

    /**
     * 会话级共享值（带来源与过期时间）
     */
    private String sessionShared;

    /**
     * 删除标识[0:未删除, 1:已删除]
     */
    private Boolean deleteFlag;
}
