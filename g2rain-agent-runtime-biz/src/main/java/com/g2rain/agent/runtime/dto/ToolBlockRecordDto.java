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
 * 工具调用拦截记录表查询DTO
 * 表名: tool_block_record
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工具调用拦截记录表 DTO")
public class ToolBlockRecordDto extends BaseDto {

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
     * 所属节点
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "所属节点（新增必填）", maxLength = 64)
    private String nodeId;

    /**
     * ReAct 轮次
     */
    @Schema(description = "ReAct 轮次")
    private Integer round;

    /**
     * 调用标识；与 tool_call_record.call_id 同源
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "调用标识；与 tool_call_record.call_id 同源（新增必填）", maxLength = 64)
    private String callId;

    /**
     * 所属技能
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 128)
    @Schema(description = "所属技能（新增必填）", maxLength = 128)
    private String skillKey;

    /**
     * 技能动作语义的版本标记；契约模式为空
     */
    @Size(max = 128)
    @Schema(description = "技能动作语义的版本标记；契约模式为空", maxLength = 128)
    private String skillPin;

    /**
     * 工具名
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 256)
    @Schema(description = "工具名（新增必填）", maxLength = 256)
    private String toolName;

    /**
     * 拦下调用的拦截器
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "拦下调用的拦截器（新增必填）", maxLength = 64)
    private String blockedBy;

    /**
     * 错误码
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "错误码（新增必填）", maxLength = 64)
    private String errorCode;

    /**
     * 扩展点给出的原因编码（Denial.code）
     */
    @Size(max = 128)
    @Schema(description = "扩展点给出的原因编码（Denial.code）", maxLength = 128)
    private String reasonCode;

    /**
     * 拒绝说明
     */
    @Size(max = 1000)
    @Schema(description = "拒绝说明", maxLength = 1000)
    private String message;

    /**
     * 给模型的下一步
     */
    @Size(max = 1000)
    @Schema(description = "给模型的下一步", maxLength = 1000)
    private String nextStep;

    /**
     * 追踪号
     */
    @Size(max = 64)
    @Schema(description = "追踪号", maxLength = 64)
    private String traceId;

    /**
     * 入参摘要（已脱敏）
     */
    @Size(max = 1073741824)
    @Schema(description = "入参摘要（已脱敏）", maxLength = 1073741824)
    private String args;
}
