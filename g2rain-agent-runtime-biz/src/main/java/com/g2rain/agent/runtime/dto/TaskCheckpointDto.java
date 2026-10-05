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
 * 任务检查点表查询DTO
 * 表名: task_checkpoint
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "任务检查点表 DTO")
public class TaskCheckpointDto extends BaseDto {

    /**
     * 机构标识
     */
    @NotNull(groups = CreateGroup.class)
    @Schema(description = "机构标识（新增必填）")
    private Long organId;

    /**
     * 所属任务；每任务至多一条，恢复/终态逻辑删除
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "所属任务；每任务至多一条，恢复/终态逻辑删除（新增必填）", maxLength = 64)
    private String taskId;

    /**
     * 挂起的节点
     */
    @Size(max = 64)
    @Schema(description = "挂起的节点", maxLength = 64)
    private String nodeId;

    /**
     * 检查点业务标识
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "检查点业务标识（新增必填）", maxLength = 64)
    private String checkpointId;

    /**
     * 检查点类型[FOLLOW_UP, APPROVAL, VERIFY]
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 32)
    @Schema(description = "检查点类型[FOLLOW_UP, APPROVAL, VERIFY]（新增必填）", maxLength = 32)
    private String type;

    /**
     * 追问/确认文案
     */
    @Size(max = 2000)
    @Schema(description = "追问/确认文案", maxLength = 2000)
    private String question;

    /**
     * 追问期望补充的字段
     */
    @Size(max = 1073741824)
    @Schema(description = "追问期望补充的字段", maxLength = 1073741824)
    private String expectedFields;

    /**
     * 候选项（来自证据）
     */
    @Size(max = 1073741824)
    @Schema(description = "候选项（来自证据）", maxLength = 1073741824)
    private String choices;

    /**
     * 恢复动作
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 1073741824)
    @Schema(description = "恢复动作（新增必填）", maxLength = 1073741824)
    private String allowedActions;

    /**
     * 快捷建议文案
     */
    @Size(max = 1073741824)
    @Schema(description = "快捷建议文案", maxLength = 1073741824)
    private String suggestions;

    /**
     * 待审批动作
     */
    @Size(max = 1073741824)
    @Schema(description = "待审批动作", maxLength = 1073741824)
    private String pendingAction;
}
