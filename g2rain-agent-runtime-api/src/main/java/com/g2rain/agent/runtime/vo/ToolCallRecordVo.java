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
 * 工具调用记录表返回VO
 * 关联表名: tool_call_record
 * 功能：封装接口返回数据，继承BaseVo复用基础字段逻辑，隔离数据库实体与前端展示层
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工具调用记录表 VO")
public class ToolCallRecordVo extends BaseVo {

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
     * 调用业务标识
     */
    @Schema(description = "调用业务标识")
    private String callId;

    /**
     * 工具名
     */
    @Schema(description = "工具名")
    private String toolName;

    /**
     * 副作用[NONE, WRITE]
     */
    @Schema(description = "副作用[NONE, WRITE]")
    private String sideEffect;

    /**
     * 全量规范化业务入参摘要
     */
    @Schema(description = "全量规范化业务入参摘要")
    private String argsHash;

    /**
     * effective 业务幂等键的 SHA-256 十六进制（契约路径值或 argsHash 兜底）
     */
    @Schema(description = "effective 业务幂等键的 SHA-256 十六进制（契约路径值或 argsHash 兜底）")
    private String idempotencyKey;

    /**
     * ReAct 轮次
     */
    @Schema(description = "ReAct 轮次")
    private Integer round;

    /**
     * 结果摘要
     */
    @Schema(description = "结果摘要")
    private String resultSummary;

    /**
     * 超长报文在 task_node_payload 中的标识
     */
    @Schema(description = "超长报文在 task_node_payload 中的标识")
    private String payloadId;

    /**
     * 调用状态[STARTED, SUCCESS, FAILED, TIMEOUT, CANCELLED, UNKNOWN, UNKNOWN_PRE_CALL]
     */
    @Schema(description = "调用状态[STARTED, SUCCESS, FAILED, TIMEOUT, CANCELLED, UNKNOWN, UNKNOWN_PRE_CALL]")
    private String callStatus;

    /**
     * 业务是否成功[0, 1]；无法判断为空
     */
    @Schema(description = "业务是否成功[0, 1]；无法判断为空")
    private Byte businessOk;

    /**
     * 是否空结果[0, 1]
     */
    @Schema(description = "是否空结果[0, 1]")
    private Byte emptyResult;

    /**
     * 失败分类[NONE, PARAMETER_FAILURE, AUTH_FAILURE, TIMEOUT, RATE_LIMIT, BUSINESS_FAILURE, TRANSPORT_FAILURE, UNKNOWN]
     */
    @Schema(description = "失败分类[NONE, PARAMETER_FAILURE, AUTH_FAILURE, TIMEOUT, RATE_LIMIT, BUSINESS_FAILURE, TRANSPORT_FAILURE, UNKNOWN]")
    private String failureKind;

    /**
     * 错误码
     */
    @Schema(description = "错误码")
    private String errorCode;

    /**
     * 错误信息
     */
    @Schema(description = "错误信息")
    private String errorMessage;

    /**
     * 耗时毫秒
     */
    @Schema(description = "耗时毫秒")
    private Long durationMs;

    /**
     * 追踪号
     */
    @Schema(description = "追踪号")
    private String traceId;

    /**
     * 请求内容（敏感值只存 handle）
     */
    @Schema(description = "请求内容（敏感值只存 handle）")
    private String request;

    /**
     * 响应内容
     */
    @Schema(description = "响应内容")
    private String response;

    /**
     * 结果中的实体
     */
    @Schema(description = "结果中的实体")
    private String entities;

    /**
     * 删除标识[0:未删除, 1:已删除]
     */
    @Schema(description = "删除标识（0 未删除，1 已删除）", example = "false")
    @ConditionalJsonIgnore(adminCompany = AdminCompanyCondition.TRUE)
    private Boolean deleteFlag;
}