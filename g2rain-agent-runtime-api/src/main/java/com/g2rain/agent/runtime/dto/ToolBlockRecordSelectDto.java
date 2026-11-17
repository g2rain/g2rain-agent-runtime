package com.g2rain.agent.runtime.dto;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.g2rain.common.model.BaseSelectListDto;
import io.swagger.v3.oas.annotations.media.Schema;


/**
 * 工具调用拦截记录表查询入参DTO
 * 用于ToolBlockRecordDao.selectList方法的条件筛选
 * 表名: tool_block_record
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工具调用拦截记录表查询入参 DTO")
public class ToolBlockRecordSelectDto extends BaseSelectListDto {

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
     * 调用标识；与 tool_call_record.call_id 同源
     */
    @Schema(description = "调用标识；与 tool_call_record.call_id 同源")
    private String callId;

    /**
     * 所属技能
     */
    @Schema(description = "所属技能")
    private String skillKey;

    /**
     * 技能动作语义的版本标记；契约模式为空
     */
    @Schema(description = "技能动作语义的版本标记；契约模式为空")
    private String skillPin;

    /**
     * 工具名
     */
    @Schema(description = "工具名")
    private String toolName;

    /**
     * 拦下调用的拦截器
     */
    @Schema(description = "拦下调用的拦截器")
    private String blockedBy;

    /**
     * 错误码
     */
    @Schema(description = "错误码")
    private String errorCode;

    /**
     * 扩展点给出的原因编码（Denial.code）
     */
    @Schema(description = "扩展点给出的原因编码（Denial.code）")
    private String reasonCode;

    /**
     * 拒绝说明
     */
    @Schema(description = "拒绝说明")
    private String message;

    /**
     * 给模型的下一步
     */
    @Schema(description = "给模型的下一步")
    private String nextStep;

    /**
     * 追踪号
     */
    @Schema(description = "追踪号")
    private String traceId;

    /**
     * 入参摘要（已脱敏）
     */
    @Schema(description = "入参摘要（已脱敏）")
    private String args;
}