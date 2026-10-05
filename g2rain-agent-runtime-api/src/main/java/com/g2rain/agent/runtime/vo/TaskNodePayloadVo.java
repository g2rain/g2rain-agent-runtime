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
 * 大报文溢出表返回VO
 * 关联表名: task_node_payload
 * 功能：封装接口返回数据，继承BaseVo复用基础字段逻辑，隔离数据库实体与前端展示层
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "大报文溢出表 VO")
public class TaskNodePayloadVo extends BaseVo {

    /**
     * 机构标识
     */
    @Schema(description = "机构标识")
    private Long organId;

    /**
     * 报文业务标识
     */
    @Schema(description = "报文业务标识")
    private String payloadId;

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
     * 报文类型[ARTIFACT, OUTPUT]
     */
    @Schema(description = "报文类型[ARTIFACT, OUTPUT]")
    private String payloadType;

    /**
     * 工具名
     */
    @Schema(description = "工具名")
    private String toolName;

    /**
     * 追踪号
     */
    @Schema(description = "追踪号")
    private String traceId;

    /**
     * 报文内容
     */
    @Schema(description = "报文内容")
    private String payloadData;

    /**
     * 删除标识[0:未删除, 1:已删除]
     */
    @Schema(description = "删除标识（0 未删除，1 已删除）", example = "false")
    @ConditionalJsonIgnore(adminCompany = AdminCompanyCondition.TRUE)
    private Boolean deleteFlag;
}