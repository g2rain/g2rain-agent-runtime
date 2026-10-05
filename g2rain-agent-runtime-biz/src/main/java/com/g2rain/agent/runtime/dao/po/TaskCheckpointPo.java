package com.g2rain.agent.runtime.dao.po;

import com.g2rain.common.model.BasePo;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


/**
 * 任务检查点表返回Po
 * 关联表名: task_checkpoint
 * 功能：封装实体数据，继承BasePo复用基础字段逻辑
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TaskCheckpointPo extends BasePo {

    /**
     * 机构标识
     */
    private Long organId;

    /**
     * 所属任务；每任务至多一条，恢复/终态逻辑删除
     */
    private String taskId;

    /**
     * 挂起的节点
     */
    private String nodeId;

    /**
     * 检查点业务标识
     */
    private String checkpointId;

    /**
     * 检查点类型[FOLLOW_UP, APPROVAL, VERIFY]
     */
    private String type;

    /**
     * 追问/确认文案
     */
    private String question;

    /**
     * 追问期望补充的字段
     */
    private String expectedFields;

    /**
     * 候选项（来自证据）
     */
    private String choices;

    /**
     * 恢复动作
     */
    private String allowedActions;

    /**
     * 快捷建议文案
     */
    private String suggestions;

    /**
     * 待审批动作
     */
    private String pendingAction;

    /**
     * 删除标识[0:未删除, 1:已删除]
     */
    private Boolean deleteFlag;
}