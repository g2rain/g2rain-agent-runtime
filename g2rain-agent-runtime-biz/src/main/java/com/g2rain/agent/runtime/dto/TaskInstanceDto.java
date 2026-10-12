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
 * 任务实例表查询DTO
 * 表名: task_instance
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "任务实例表 DTO")
public class TaskInstanceDto extends BaseDto {

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
     * 任务业务标识
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "任务业务标识（新增必填）", maxLength = 64)
    private String taskId;

    /**
     * 绑定的 Agent 主键
     */
    @Schema(description = "绑定的 Agent 主键")
    private Long agentId;

    /**
     * 任务目标摘要
     */
    @Size(max = 2000)
    @Schema(description = "任务目标摘要", maxLength = 2000)
    private String goalSummary;

    /**
     * 开任务时的用户原文
     */
    @Schema(description = "开任务时的用户原文")
    private String request;

    /**
     * 触发开任务的消息标识
     */
    @Size(max = 64)
    @Schema(description = "触发开任务的消息标识", maxLength = 64)
    private String sourceMessageId;

    /**
     * 任务状态[RUNNING, SUSPENDED, SUCCEEDED, FAILED, CANCELED]
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 32)
    @Schema(description = "任务状态[RUNNING, SUSPENDED, SUCCEEDED, FAILED, CANCELED]（新增必填）", maxLength = 32)
    private String status;

    /**
     * 当前节点
     */
    @Size(max = 64)
    @Schema(description = "当前节点", maxLength = 64)
    private String currentNodeId;

    /**
     * 终态对外文案
     */
    @Size(max = 2000)
    @Schema(description = "终态对外文案", maxLength = 2000)
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
    @Size(max = 64)
    @Schema(description = "开任务入站追踪号", maxLength = 64)
    private String traceId;

    /**
     * 本轮栅栏版本，落库按此单调推进
     */
    @NotNull(groups = CreateGroup.class)
    @Schema(description = "本轮栅栏版本，落库按此单调推进（新增必填）")
    private Long stateVersion;

    /**
     * 开跑时固定的 skill 版本
     */
    @Size(max = 1073741824)
    @Schema(description = "开跑时固定的 skill 版本", maxLength = 1073741824)
    private String skillVersions;

    /**
     * 任务级共享值
     */
    @Size(max = 1073741824)
    @Schema(description = "任务级共享值", maxLength = 1073741824)
    private String shared;

    /**
     * 前情
     */
    @Size(max = 1073741824)
    @Schema(description = "前情", maxLength = 1073741824)
    private String prior;

    /**
     * 结束时的任务记录
     */
    @Size(max = 1073741824)
    @Schema(description = "结束时的任务记录", maxLength = 1073741824)
    private String record;
}
