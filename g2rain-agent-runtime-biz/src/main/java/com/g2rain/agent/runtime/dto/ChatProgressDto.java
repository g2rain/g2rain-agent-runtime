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
 * 执行过程步骤表查询DTO
 * 表名: chat_progress
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "执行过程步骤表 DTO")
public class ChatProgressDto extends BaseDto {

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
     * 所属轮次追踪号；与最终回复的 trace_id 关联
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "所属轮次追踪号；与最终回复的 trace_id 关联（新增必填）", maxLength = 64)
    private String traceId;

    /**
     * 关联任务；可空
     */
    @Size(max = 64)
    @Schema(description = "关联任务；可空", maxLength = 64)
    private String taskId;

    /**
     * 步骤标识；本轮内唯一
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "步骤标识；本轮内唯一（新增必填）", maxLength = 64)
    private String stepId;

    /**
     * 上级步骤；顶层为空
     */
    @Size(max = 64)
    @Schema(description = "上级步骤；顶层为空", maxLength = 64)
    private String parentStepId;

    /**
     * 本轮内开始顺序
     */
    @NotNull(groups = CreateGroup.class)
    @Schema(description = "本轮内开始顺序（新增必填）")
    private Integer seq;

    /**
     * 环节[ROUTE, PLAN, NODE, ROUND, MODEL, TOOL, VERIFY]
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 16)
    @Schema(description = "环节[ROUTE, PLAN, NODE, ROUND, MODEL, TOOL, VERIFY]（新增必填）", maxLength = 16)
    private String stage;

    /**
     * 状态[RUNNING, DONE, FAILED]
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 16)
    @Schema(description = "状态[RUNNING, DONE, FAILED]（新增必填）", maxLength = 16)
    private String status;

    /**
     * 标题
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 256)
    @Schema(description = "标题（新增必填）", maxLength = 256)
    private String title;

    /**
     * 补充说明
     */
    @Size(max = 1000)
    @Schema(description = "补充说明", maxLength = 1000)
    private String detail;

    /**
     * 所属节点
     */
    @Size(max = 64)
    @Schema(description = "所属节点", maxLength = 64)
    private String nodeId;

    /**
     * ReAct 轮次
     */
    @Schema(description = "ReAct 轮次")
    private Integer round;

    /**
     * 失败错误码
     */
    @Size(max = 64)
    @Schema(description = "失败错误码", maxLength = 64)
    private String errorCode;

    /**
     * 开始时间 epoch ms
     */
    @NotNull(groups = CreateGroup.class)
    @Schema(description = "开始时间 epoch ms（新增必填）")
    private Long startedAtMs;

    /**
     * 耗时毫秒
     */
    @Schema(description = "耗时毫秒")
    private Integer elapsedMs;

    /**
     * 排查信息（token、参数摘要、上游原文）；不下发 chat，供会话追踪查看
     */
    @Size(max = 1073741824)
    @Schema(description = "排查信息（token、参数摘要、上游原文）；不下发 chat，供会话追踪查看", maxLength = 1073741824)
    private String extra;
}
