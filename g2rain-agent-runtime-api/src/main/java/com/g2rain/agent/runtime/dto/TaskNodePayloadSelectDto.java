package com.g2rain.agent.runtime.dto;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.g2rain.common.model.BaseSelectListDto;
import io.swagger.v3.oas.annotations.media.Schema;


/**
 * 大报文溢出表查询入参DTO
 * 用于TaskNodePayloadDao.selectList方法的条件筛选
 * 表名: task_node_payload
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "大报文溢出表查询入参 DTO")
public class TaskNodePayloadSelectDto extends BaseSelectListDto {

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
}