package com.g2rain.agent.runtime.vo;

import com.g2rain.common.json.AdminCompanyCondition;
import com.g2rain.common.json.ConditionalJsonIgnore;
import com.g2rain.common.model.BaseVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 聊天会话表返回VO
 * 关联表名: chat_conversation
 * 功能：封装接口返回数据，继承BaseVo复用基础字段逻辑，隔离数据库实体与前端展示层
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "聊天会话表 VO")
public class ChatConversationVo extends BaseVo {

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

    /**
     * 删除标识[0:未删除, 1:已删除]
     */
    @Schema(description = "删除标识（0 未删除，1 已删除）", example = "false")
    @ConditionalJsonIgnore(adminCompany = AdminCompanyCondition.TRUE)
    private Boolean deleteFlag;
}
