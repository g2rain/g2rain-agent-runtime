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
 * 大报文溢出表查询DTO
 * 表名: task_node_payload
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "大报文溢出表 DTO")
public class TaskNodePayloadDto extends BaseDto {

    /**
     * 机构标识
     */
    @NotNull(groups = CreateGroup.class)
    @Schema(description = "机构标识（新增必填）")
    private Long organId;

    /**
     * 报文业务标识
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "报文业务标识（新增必填）", maxLength = 64)
    private String payloadId;

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
     * 报文类型[ARTIFACT, OUTPUT]
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 32)
    @Schema(description = "报文类型[ARTIFACT, OUTPUT]（新增必填）", maxLength = 32)
    private String payloadType;

    /**
     * 工具名
     */
    @Size(max = 256)
    @Schema(description = "工具名", maxLength = 256)
    private String toolName;

    /**
     * 追踪号
     */
    @Size(max = 64)
    @Schema(description = "追踪号", maxLength = 64)
    private String traceId;

    /**
     * 报文内容
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 1073741824)
    @Schema(description = "报文内容（新增必填）", maxLength = 1073741824)
    private String payloadData;
}
