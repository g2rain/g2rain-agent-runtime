package com.g2rain.agent.runtime.dao.po;

import com.g2rain.common.model.BasePo;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 任务实例表返回Po
 * 关联表名: task_instance
 * 功能：封装实体数据，继承BasePo复用基础字段逻辑
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TaskInstancePo extends BasePo {

    /**
     * 机构标识
     */
    private Long organId;

    /**
     * 所属会话
     */
    private String conversationId;

    /**
     * 任务业务标识
     */
    private String taskId;

    /**
     * 绑定的 Agent 主键
     */
    private Long agentId;

    /**
     * 任务目标摘要
     */
    private String goalSummary;

    /**
     * 开任务时的用户原文
     */
    private String request;

    /**
     * 触发开任务的消息标识
     */
    private String sourceMessageId;

    /**
     * 任务状态[RUNNING, SUSPENDED, SUCCEEDED, FAILED, CANCELED]
     */
    private String status;

    /**
     * 当前节点
     */
    private String currentNodeId;

    /**
     * 终态对外文案
     */
    private String finalMessage;

    /**
     * 开始时间
     */
    private LocalDateTime startedTime;

    /**
     * 最近一次挂起时间
     */
    private LocalDateTime suspendedTime;

    /**
     * 结束时间
     */
    private LocalDateTime finishedTime;

    /**
     * 开任务入站追踪号
     */
    private String traceId;

    /**
     * 本轮栅栏版本，落库按此单调推进
     */
    private Long stateVersion;

    /**
     * 开跑时固定的 skill 版本
     */
    private String skillVersions;

    /**
     * 任务级共享值
     */
    private String shared;

    /**
     * 前情
     */
    private String prior;

    /**
     * 结束时的任务记录
     */
    private String taskRecord;

    /**
     * 删除标识[0:未删除, 1:已删除]
     */
    private Boolean deleteFlag;
}
