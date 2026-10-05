package com.g2rain.agent.runtime.dto;

import com.g2rain.common.model.BaseSelectListDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 任务图步骤表查询入参DTO
 * 用于TaskGraphNodeDao.selectList方法的条件筛选
 * 表名: task_graph_node
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "任务图步骤表查询入参 DTO")
public class TaskGraphNodeSelectDto extends BaseSelectListDto {

    /**
     * 机构标识
     */
    @Schema(description = "机构标识")
    private Long organId;

    /**
     * 所属任务
     */
    @Schema(description = "所属任务")
    private String taskId;

    /**
     * 步骤业务标识
     */
    @Schema(description = "步骤业务标识")
    private String nodeId;

    /**
     * 步骤标题
     */
    @Schema(description = "步骤标题")
    private String title;

    /**
     * 步骤硬目标（给 ReAct）
     */
    @Schema(description = "步骤硬目标（给 ReAct）")
    private String objective;

    /**
     * 技能键
     */
    @Schema(description = "技能键")
    private String skillKey;

    /**
     * 所属 stage
     */
    @Schema(description = "所属 stage")
    private String stage;

    /**
     * 委派深度
     */
    @Schema(description = "委派深度")
    private Integer delegateDepth;

    /**
     * 步骤状态[PENDING, READY, RUNNING, WAITING_DELEGATE, SUSPENDED, DONE, FAILED, SKIPPED, CANCELLED, TIMEOUT]
     */
    @Schema(description = "步骤状态[PENDING, READY, RUNNING, WAITING_DELEGATE, SUSPENDED, DONE, FAILED, SKIPPED, CANCELLED, TIMEOUT]")
    private String status;

    /**
     * 输入摘要
     */
    @Schema(description = "输入摘要")
    private String inputSummary;

    /**
     * 输出摘要
     */
    @Schema(description = "输出摘要")
    private String outputSummary;

    /**
     * 错误码
     */
    @Schema(description = "错误码")
    private String errorCode;

    /**
     * 错误信息
     */
    @Schema(description = "错误信息")
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
    @Schema(description = "输入（带来源）")
    private String inputs;

    /**
     * 输出（带来源）
     */
    @Schema(description = "输出（带来源）")
    private String outputs;

    /**
     * 私有便签、补参义务、轮次
     */
    @Schema(description = "私有便签、补参义务、轮次")
    private String state;
}
