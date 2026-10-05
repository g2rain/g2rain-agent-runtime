package com.g2rain.agent.runtime.dao.po;

import com.g2rain.common.model.BasePo;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 任务恢复动作审计表返回Po
 * 关联表名: task_resume_record
 * 功能：封装实体数据，继承BasePo复用基础字段逻辑
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TaskResumeRecordPo extends BasePo {

    /**
     * 机构标识
     */
    private Long organId;

    /**
     * 所属任务
     */
    private String taskId;

    /**
     * 关联节点
     */
    private String nodeId;

    /**
     * 恢复动作业务标识
     */
    private String resumeId;

    /**
     * 关联检查点
     */
    private String checkpointId;

    /**
     * 关联消息
     */
    private String messageId;

    /**
     * 恢复动作[REPLY, CONFIRM, REJECT, CANCEL]
     */
    private String resumeAction;

    /**
     * 动作时间
     */
    private LocalDateTime actedAt;

    /**
     * 动作载荷
     */
    private String payload;

    /**
     * 删除标识[0:未删除, 1:已删除]
     */
    private Boolean deleteFlag;
}
