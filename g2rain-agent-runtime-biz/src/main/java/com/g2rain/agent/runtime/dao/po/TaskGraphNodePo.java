package com.g2rain.agent.runtime.dao.po;

import com.g2rain.common.model.BasePo;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 任务图步骤表返回Po
 * 关联表名: task_graph_node
 * 功能：封装实体数据，继承BasePo复用基础字段逻辑
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TaskGraphNodePo extends BasePo {

    /**
     * 机构标识
     */
    private Long organId;

    /**
     * 所属任务
     */
    private String taskId;

    /**
     * 步骤业务标识
     */
    private String nodeId;

    /**
     * 步骤标题
     */
    private String title;

    /**
     * 步骤硬目标（给 ReAct）
     */
    private String objective;

    /**
     * 技能键
     */
    private String skillKey;

    /**
     * 所属 stage
     */
    private String stage;

    /**
     * 委派深度
     */
    private Integer delegateDepth;

    /**
     * 步骤状态[PENDING, READY, RUNNING, WAITING_DELEGATE, SUSPENDED, DONE, FAILED, SKIPPED, CANCELLED, TIMEOUT]
     */
    private String status;

    /**
     * 输入摘要
     */
    private String inputSummary;

    /**
     * 输出摘要
     */
    private String outputSummary;

    /**
     * 错误码
     */
    private String errorCode;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 开始时间
     */
    private LocalDateTime startedTime;

    /**
     * 结束时间
     */
    private LocalDateTime finishedTime;

    /**
     * 输入（带来源）
     */
    private String inputs;

    /**
     * 输出（带来源）
     */
    private String outputs;

    /**
     * 私有便签、补参义务、轮次
     */
    private String state;

    /**
     * 删除标识[0:未删除, 1:已删除]
     */
    private Boolean deleteFlag;
}
