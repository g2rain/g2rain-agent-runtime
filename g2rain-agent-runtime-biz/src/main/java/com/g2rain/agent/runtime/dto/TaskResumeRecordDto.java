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
 * 任务恢复动作审计表查询DTO
 * 表名: task_resume_record
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "任务恢复动作审计表 DTO")
public class TaskResumeRecordDto extends BaseDto {

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
     * 关联节点
     */
    @Size(max = 64)
    @Schema(description = "关联节点", maxLength = 64)
    private String nodeId;

    /**
     * 恢复动作业务标识
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "恢复动作业务标识（新增必填）", maxLength = 64)
    private String resumeId;

    /**
     * 关联检查点
     */
    @Size(max = 64)
    @Schema(description = "关联检查点", maxLength = 64)
    private String checkpointId;

    /**
     * 关联消息
     */
    @Size(max = 64)
    @Schema(description = "关联消息", maxLength = 64)
    private String messageId;

    /**
     * 恢复动作[REPLY, CONFIRM, REJECT, CANCEL]
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 32)
    @Schema(description = "恢复动作[REPLY, CONFIRM, REJECT, CANCEL]（新增必填）", maxLength = 32)
    private String resumeAction;

    /**
     * 动作时间
     */
    @Schema(description = "动作时间")
    private LocalDateTime actedAt;

}
