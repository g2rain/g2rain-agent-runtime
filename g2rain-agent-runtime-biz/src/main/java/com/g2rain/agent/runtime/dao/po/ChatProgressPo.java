package com.g2rain.agent.runtime.dao.po;

import com.g2rain.common.model.BasePo;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


/**
 * 执行过程步骤表返回Po
 * 关联表名: chat_progress
 * 功能：封装实体数据，继承BasePo复用基础字段逻辑
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ChatProgressPo extends BasePo {

    /**
     * 机构标识
     */
    private Long organId;

    /**
     * 所属会话
     */
    private String conversationId;

    /**
     * 所属轮次追踪号；与最终回复的 trace_id 关联
     */
    private String traceId;

    /**
     * 关联任务；可空
     */
    private String taskId;

    /**
     * 步骤标识；本轮内唯一
     */
    private String stepId;

    /**
     * 上级步骤；顶层为空
     */
    private String parentStepId;

    /**
     * 本轮内开始顺序
     */
    private Integer seq;

    /**
     * 环节[ROUTE, PLAN, NODE, ROUND, MODEL, TOOL, VERIFY]
     */
    private String stage;

    /**
     * 状态[RUNNING, DONE, FAILED]
     */
    private String status;

    /**
     * 标题
     */
    private String title;

    /**
     * 补充说明
     */
    private String detail;

    /**
     * 所属节点
     */
    private String nodeId;

    /**
     * ReAct 轮次
     */
    private Integer round;

    /**
     * 失败错误码
     */
    private String errorCode;

    /**
     * 开始时间 epoch ms
     */
    private Long startedAtMs;

    /**
     * 耗时毫秒
     */
    private Integer elapsedMs;

    /**
     * 排查信息（token、参数摘要、上游原文）；不下发 chat，供会话追踪查看
     */
    private String extra;

    /**
     * 删除标识[0:未删除, 1:已删除]
     */
    private Boolean deleteFlag;
}