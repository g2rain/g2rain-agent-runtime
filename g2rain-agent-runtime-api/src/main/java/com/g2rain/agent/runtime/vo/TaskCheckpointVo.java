package com.g2rain.agent.runtime.vo;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.g2rain.common.model.BaseVo;
import io.swagger.v3.oas.annotations.media.Schema;

import com.g2rain.common.json.ConditionalJsonIgnore;
import com.g2rain.common.json.AdminCompanyCondition;

/**
 * 任务检查点表返回VO
 * 关联表名: task_checkpoint
 * 功能：封装接口返回数据，继承BaseVo复用基础字段逻辑，隔离数据库实体与前端展示层
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "任务检查点表 VO")
public class TaskCheckpointVo extends BaseVo {

    /**
     * 机构标识
     */
    @Schema(description = "机构标识")
    private Long organId;

    /**
     * 所属任务；每任务至多一条，恢复/终态逻辑删除
     */
    @Schema(description = "所属任务；每任务至多一条，恢复/终态逻辑删除")
    private String taskId;

    /**
     * 挂起的节点
     */
    @Schema(description = "挂起的节点")
    private String nodeId;

    /**
     * 检查点业务标识
     */
    @Schema(description = "检查点业务标识")
    private String checkpointId;

    /**
     * 检查点类型[FOLLOW_UP, APPROVAL, VERIFY]
     */
    @Schema(description = "检查点类型[FOLLOW_UP, APPROVAL, VERIFY]")
    private String type;

    /**
     * 追问/确认文案
     */
    @Schema(description = "追问/确认文案")
    private String question;

    /**
     * 追问期望补充的字段
     */
    @Schema(description = "追问期望补充的字段")
    private String expectedFields;

    /**
     * 候选项（来自证据）
     */
    @Schema(description = "候选项（来自证据）")
    private String choices;

    /**
     * 恢复动作
     */
    @Schema(description = "恢复动作")
    private String allowedActions;

    /**
     * 快捷建议文案
     */
    @Schema(description = "快捷建议文案")
    private String suggestions;

    /**
     * 待审批动作
     */
    @Schema(description = "待审批动作")
    private String pendingAction;

    /**
     * 删除标识[0:未删除, 1:已删除]
     */
    @Schema(description = "删除标识（0 未删除，1 已删除）", example = "false")
    @ConditionalJsonIgnore(adminCompany = AdminCompanyCondition.TRUE)
    private Boolean deleteFlag;
}