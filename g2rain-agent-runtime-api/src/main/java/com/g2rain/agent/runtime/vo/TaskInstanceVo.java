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
 * 任务实例表返回VO
 * 关联表名: task_instance
 * 功能：封装接口返回数据，继承BaseVo复用基础字段逻辑，隔离数据库实体与前端展示层
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "任务实例表 VO")
public class TaskInstanceVo extends BaseVo {

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
     * 任务业务标识
     */
    @Schema(description = "任务业务标识")
    private String taskId;

    /**
     * 绑定的 Agent 主键
     */
    @Schema(description = "绑定的 Agent 主键")
    private Long agentId;

    /**
     * 任务目标摘要
     */
    @Schema(description = "任务目标摘要")
    private String goalSummary;

    /**
     * 开任务时的用户原文
     */
    @Schema(description = "开任务时的用户原文")
    private String request;

    /**
     * 触发开任务的消息标识
     */
    @Schema(description = "触发开任务的消息标识")
    private String sourceMessageId;

    /**
     * 任务状态[RUNNING, SUSPENDED, SUCCEEDED, FAILED, CANCELED]
     */
    @Schema(description = "任务状态[RUNNING, SUSPENDED, SUCCEEDED, FAILED, CANCELED]")
    private String status;

    /**
     * 当前节点
     */
    @Schema(description = "当前节点")
    private String currentNodeId;

    /**
     * 终态对外文案
     */
    @Schema(description = "终态对外文案")
    private String finalMessage;

    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    private LocalDateTime startedTime;

    /**
     * 最近一次挂起时间
     */
    @Schema(description = "最近一次挂起时间")
    private LocalDateTime suspendedTime;

    /**
     * 结束时间
     */
    @Schema(description = "结束时间")
    private LocalDateTime finishedTime;

    /**
     * 开任务入站追踪号
     */
    @Schema(description = "开任务入站追踪号")
    private String traceId;

    /**
     * 本轮栅栏版本，落库按此单调推进
     */
    @Schema(description = "本轮栅栏版本，落库按此单调推进")
    private Long stateVersion;

    /**
     * 开跑时固定的 skill 版本
     */
    @Schema(description = "开跑时固定的 skill 版本")
    private String skillVersions;

    /**
     * 任务级共享值
     */
    @Schema(description = "任务级共享值")
    private String shared;

    /**
     * 前情
     */
    @Schema(description = "前情")
    private String prior;

    /**
     * 结束时的任务记录
     */
    @Schema(description = "结束时的任务记录")
    private String record;

    /**
     * 删除标识[0:未删除, 1:已删除]
     */
    @Schema(description = "删除标识（0 未删除，1 已删除）", example = "false")
    @ConditionalJsonIgnore(adminCompany = AdminCompanyCondition.TRUE)
    private Boolean deleteFlag;
}
