package com.g2rain.agent.runtime.dto;

import com.g2rain.common.model.BaseSelectListDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务恢复动作审计表查询入参DTO
 * 用于TaskResumeRecordDao.selectList方法的条件筛选
 * 表名: task_resume_record
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "任务恢复动作审计表查询入参 DTO")
public class TaskResumeRecordSelectDto extends BaseSelectListDto {

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
     * 多任务筛选（IN）
     */
    @Schema(description = "多任务筛选")
    private List<String> taskIds;

    /**
     * 关联节点
     */
    @Schema(description = "关联节点")
    private String nodeId;

    /**
     * 恢复动作业务标识
     */
    @Schema(description = "恢复动作业务标识")
    private String resumeId;

    /**
     * 关联检查点
     */
    @Schema(description = "关联检查点")
    private String checkpointId;

    /**
     * 关联消息
     */
    @Schema(description = "关联消息")
    private String messageId;

    /**
     * 恢复动作[REPLY, CONFIRM, REJECT, CANCEL]
     */
    @Schema(description = "恢复动作[REPLY, CONFIRM, REJECT, CANCEL]")
    private String resumeAction;

    /**
     * 动作时间
     */
    @Schema(description = "动作时间")
    private LocalDateTime actedAt;

    /**
     * 动作载荷
     */
    @Schema(description = "动作载荷")
    private String payload;
}
