package com.g2rain.agent.runtime.dto;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.g2rain.common.model.BaseSelectListDto;
import io.swagger.v3.oas.annotations.media.Schema;


/**
 * 执行过程步骤表查询入参DTO
 * 用于ChatProgressDao.selectList方法的条件筛选
 * 表名: chat_progress
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "执行过程步骤表查询入参 DTO")
public class ChatProgressSelectDto extends BaseSelectListDto {

    /**
     * 机构标识
     */
    @Schema(description = "机构标识")
    private Long organId;

    /**
     * 所属会话
     */
    @Schema(description = "所属会话")
    private String conversationId;

    /**
     * 所属轮次追踪号；与最终回复的 trace_id 关联
     */
    @Schema(description = "所属轮次追踪号；与最终回复的 trace_id 关联")
    private String traceId;

    /**
     * 关联任务；可空
     */
    @Schema(description = "关联任务；可空")
    private String taskId;

    /**
     * 步骤标识；本轮内唯一
     */
    @Schema(description = "步骤标识；本轮内唯一")
    private String stepId;

    /**
     * 上级步骤；顶层为空
     */
    @Schema(description = "上级步骤；顶层为空")
    private String parentStepId;

    /**
     * 本轮内开始顺序
     */
    @Schema(description = "本轮内开始顺序")
    private Integer seq;

    /**
     * 环节[ROUTE, PLAN, NODE, ROUND, MODEL, TOOL, VERIFY]
     */
    @Schema(description = "环节[ROUTE, PLAN, NODE, ROUND, MODEL, TOOL, VERIFY]")
    private String stage;

    /**
     * 状态[RUNNING, DONE, FAILED]
     */
    @Schema(description = "状态[RUNNING, DONE, FAILED]")
    private String status;

    /**
     * 标题
     */
    @Schema(description = "标题")
    private String title;

    /**
     * 补充说明
     */
    @Schema(description = "补充说明")
    private String detail;

    /**
     * 所属节点
     */
    @Schema(description = "所属节点")
    private String nodeId;

    /**
     * ReAct 轮次
     */
    @Schema(description = "ReAct 轮次")
    private Integer round;

    /**
     * 失败错误码
     */
    @Schema(description = "失败错误码")
    private String errorCode;

    /**
     * 开始时间 epoch ms
     */
    @Schema(description = "开始时间 epoch ms")
    private Long startedAtMs;

    /**
     * 耗时毫秒
     */
    @Schema(description = "耗时毫秒")
    private Integer elapsedMs;

    /**
     * 排查信息（token、参数摘要、上游原文）；不下发 chat，供会话追踪查看
     */
    @Schema(description = "排查信息（token、参数摘要、上游原文）；不下发 chat，供会话追踪查看")
    private String extra;
}