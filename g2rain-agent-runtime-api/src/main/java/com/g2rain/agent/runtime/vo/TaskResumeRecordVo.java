package com.g2rain.agent.runtime.vo;

import com.g2rain.common.json.AdminCompanyCondition;
import com.g2rain.common.json.ConditionalJsonIgnore;
import com.g2rain.common.model.BaseVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 任务恢复动作审计表返回VO
 * 关联表名: task_resume_record
 * 功能：封装接口返回数据，继承BaseVo复用基础字段逻辑，隔离数据库实体与前端展示层
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "任务恢复动作审计表 VO")
public class TaskResumeRecordVo extends BaseVo {

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
     * 删除标识[0:未删除, 1:已删除]
     */
    @Schema(description = "删除标识（0 未删除，1 已删除）", example = "false")
    @ConditionalJsonIgnore(adminCompany = AdminCompanyCondition.TRUE)
    private Boolean deleteFlag;
}
