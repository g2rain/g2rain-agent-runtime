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
 * 任务图步骤表查询DTO
 * 表名: task_graph_node
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "任务图步骤表 DTO")
public class TaskGraphNodeDto extends BaseDto {

    /**
     * 机构标识
     */
    @NotNull(groups = CreateGroup.class)
    @Schema(description = "机构标识（新增必填）")
    private Long organId;

    /**
     * 所属任务
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "所属任务（新增必填）", maxLength = 64)
    private String taskId;

    /**
     * 步骤业务标识
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "步骤业务标识（新增必填）", maxLength = 64)
    private String nodeId;

    /**
     * 步骤标题
     */
    @Size(max = 256)
    @Schema(description = "步骤标题", maxLength = 256)
    private String title;

    /**
     * 步骤硬目标（给 ReAct）
     */
    @Size(max = 2000)
    @Schema(description = "步骤硬目标（给 ReAct）", maxLength = 2000)
    private String objective;

    /**
     * 技能键
     */
    @Size(max = 128)
    @Schema(description = "技能键", maxLength = 128)
    private String skillKey;

    /**
     * 所属 stage
     */
    @Size(max = 64)
    @Schema(description = "所属 stage", maxLength = 64)
    private String stage;

    /**
     * 委派深度
     */
    @NotNull(groups = CreateGroup.class)
    @Schema(description = "委派深度（新增必填）")
    private Integer delegateDepth;

    /**
     * 步骤状态[PENDING, READY, RUNNING, WAITING_DELEGATE, SUSPENDED, DONE, FAILED, SKIPPED, CANCELLED, TIMEOUT]
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 32)
    @Schema(description = "步骤状态[PENDING, READY, RUNNING, WAITING_DELEGATE, SUSPENDED, DONE, FAILED, SKIPPED, CANCELLED, TIMEOUT]（新增必填）", maxLength = 32)
    private String status;

    /**
     * 输入摘要
     */
    @Size(max = 1000)
    @Schema(description = "输入摘要", maxLength = 1000)
    private String inputSummary;

    /**
     * 输出摘要
     */
    @Size(max = 1000)
    @Schema(description = "输出摘要", maxLength = 1000)
    private String outputSummary;

    /**
     * 错误码
     */
    @Size(max = 64)
    @Schema(description = "错误码", maxLength = 64)
    private String errorCode;

    /**
     * 错误信息
     */
    @Size(max = 1000)
    @Schema(description = "错误信息", maxLength = 1000)
    private String errorMessage;

    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    private LocalDateTime startedTime;

    /**
     * 结束时间
     */
    @Schema(description = "结束时间")
    private LocalDateTime finishedTime;

    /**
     * 输入（带来源）
     */
    @Size(max = 1073741824)
    @Schema(description = "输入（带来源）", maxLength = 1073741824)
    private String inputs;

    /**
     * 输出（带来源）
     */
    @Size(max = 1073741824)
    @Schema(description = "输出（带来源）", maxLength = 1073741824)
    private String outputs;

    /**
     * 私有便签、补参义务、轮次
     */
    @Size(max = 1073741824)
    @Schema(description = "私有便签、补参义务、轮次", maxLength = 1073741824)
    private String state;
}
