package com.g2rain.agent.runtime.dto;

import com.g2rain.common.model.BaseDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.g2rain.common.validation.CreateGroup;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 工具调用记录表查询DTO
 * 表名: tool_call_record
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工具调用记录表 DTO")
public class ToolCallRecordDto extends BaseDto {

    /**
     * 机构标识
     */
    @NotNull(groups = CreateGroup.class)
    @Schema(description = "机构标识（新增必填）")
    private Long organId;

    /**
     * 所属任务
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "所属任务（新增必填）", maxLength = 64)
    private String taskId;

    /**
     * 所属节点
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "所属节点（新增必填）", maxLength = 64)
    private String nodeId;

    /**
     * 调用业务标识
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "调用业务标识（新增必填）", maxLength = 64)
    private String callId;

    /**
     * 工具名
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 256)
    @Schema(description = "工具名（新增必填）", maxLength = 256)
    private String toolName;

    /**
     * 副作用[NONE, WRITE]
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 16)
    @Schema(description = "副作用[NONE, WRITE]（新增必填）", maxLength = 16)
    private String sideEffect;

    /**
     * 全量规范化业务入参摘要
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 32)
    @Schema(description = "全量规范化业务入参摘要（新增必填）", maxLength = 32)
    private String argsHash;

    /**
     * effective 业务幂等键的 SHA-256 十六进制（契约路径值或 argsHash 兜底）
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 64)
    @Schema(description = "effective 业务幂等键的 SHA-256 十六进制（契约路径值或 argsHash 兜底）（新增必填）", maxLength = 64)
    private String idempotencyKey;

    /**
     * ReAct 轮次
     */
    @Schema(description = "ReAct 轮次")
    private Integer round;

    /**
     * 结果摘要
     */
    @Size(max = 1000)
    @Schema(description = "结果摘要", maxLength = 1000)
    private String resultSummary;

    /**
     * 超长报文在 task_node_payload 中的标识
     */
    @Size(max = 64)
    @Schema(description = "超长报文在 task_node_payload 中的标识", maxLength = 64)
    private String payloadId;

    /**
     * 调用状态[STARTED, SUCCESS, FAILED, TIMEOUT, CANCELLED, UNKNOWN, UNKNOWN_PRE_CALL]
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 32)
    @Schema(description = "调用状态[STARTED, SUCCESS, FAILED, TIMEOUT, CANCELLED, UNKNOWN, UNKNOWN_PRE_CALL]（新增必填）", maxLength = 32)
    private String callStatus;

    /**
     * 业务是否成功[0, 1]；无法判断为空
     */
    @Schema(description = "业务是否成功[0, 1]；无法判断为空")
    private Byte businessOk;

    /**
     * 是否空结果[0, 1]
     */
    @NotNull(groups = CreateGroup.class)
    @Schema(description = "是否空结果[0, 1]（新增必填）")
    private Byte emptyResult;

    /**
     * 失败分类[NONE, PARAMETER_FAILURE, AUTH_FAILURE, TIMEOUT, RATE_LIMIT, BUSINESS_FAILURE, TRANSPORT_FAILURE, UNKNOWN]
     */
    @NotBlank(groups = CreateGroup.class)
    @Size(max = 32)
    @Schema(description = "失败分类[NONE, PARAMETER_FAILURE, AUTH_FAILURE, TIMEOUT, RATE_LIMIT, BUSINESS_FAILURE, TRANSPORT_FAILURE, UNKNOWN]（新增必填）", maxLength = 32)
    private String failureKind;

    /**
     * 错误码
     */
    @Size(max = 64)
    @Schema(description = "错误码", maxLength = 64)
    private String errorCode;

    /**
     * 错误信息
     */
    @Size(max = 1000)
    @Schema(description = "错误信息", maxLength = 1000)
    private String errorMessage;

    /**
     * 耗时毫秒
     */
    @Schema(description = "耗时毫秒")
    private Long durationMs;

    /**
     * 追踪号
     */
    @Size(max = 64)
    @Schema(description = "追踪号", maxLength = 64)
    private String traceId;

    /**
     * 请求内容（敏感值只存 handle）
     */
    @Size(max = 1073741824)
    @Schema(description = "请求内容（敏感值只存 handle）", maxLength = 1073741824)
    private String request;

    /**
     * 响应内容
     */
    @Size(max = 1073741824)
    @Schema(description = "响应内容", maxLength = 1073741824)
    private String response;

    /**
     * 结果中的实体
     */
    @Size(max = 1073741824)
    @Schema(description = "结果中的实体", maxLength = 1073741824)
    private String entities;
}
