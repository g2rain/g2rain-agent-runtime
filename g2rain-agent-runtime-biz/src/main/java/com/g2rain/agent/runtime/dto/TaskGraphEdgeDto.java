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
 * 任务图依赖边表查询DTO
 * 表名: task_graph_edge
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "任务图依赖边表 DTO")
public class TaskGraphEdgeDto extends BaseDto {

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
     * 前置步骤
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "前置步骤（新增必填）", maxLength = 64)
    private String fromNodeId;

    /**
     * 后继步骤
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "后继步骤（新增必填）", maxLength = 64)
    private String toNodeId;

    /**
     * 边类型[NORMAL, DELEGATE]
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 32)
    @Schema(description = "边类型[NORMAL, DELEGATE]（新增必填）", maxLength = 32)
    private String edgeType;
}
