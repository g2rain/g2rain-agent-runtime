package com.g2rain.agent.runtime.dao.po;

import com.g2rain.common.model.BasePo;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


/**
 * 工具调用拦截记录表返回Po
 * 关联表名: tool_block_record
 * 功能：封装实体数据，继承BasePo复用基础字段逻辑
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ToolBlockRecordPo extends BasePo {

    /**
     * 机构标识
     */
    private Long organId;

    /**
     * 所属任务
     */
    private String taskId;

    /**
     * 所属节点
     */
    private String nodeId;

    /**
     * ReAct 轮次
     */
    private Integer round;

    /**
     * 调用标识；与 tool_call_record.call_id 同源
     */
    private String callId;

    /**
     * 所属技能
     */
    private String skillKey;

    /**
     * 技能动作语义的版本标记；契约模式为空
     */
    private String skillPin;

    /**
     * 工具名
     */
    private String toolName;

    /**
     * 拦下调用的拦截器
     */
    private String blockedBy;

    /**
     * 错误码
     */
    private String errorCode;

    /**
     * 扩展点给出的原因编码（Denial.code）
     */
    private String reasonCode;

    /**
     * 拒绝说明
     */
    private String message;

    /**
     * 给模型的下一步
     */
    private String nextStep;

    /**
     * 追踪号
     */
    private String traceId;

    /**
     * 入参摘要（已脱敏）
     */
    private String args;

    /**
     * 删除标识[0:未删除, 1:已删除]
     */
    private Boolean deleteFlag;
}