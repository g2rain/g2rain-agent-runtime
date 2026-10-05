package com.g2rain.agent.runtime.dao.po;

import com.g2rain.common.model.BasePo;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


/**
 * 大报文溢出表返回Po
 * 关联表名: task_node_payload
 * 功能：封装实体数据，继承BasePo复用基础字段逻辑
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TaskNodePayloadPo extends BasePo {

    /**
     * 机构标识
     */
    private Long organId;

    /**
     * 报文业务标识
     */
    private String payloadId;

    /**
     * 所属任务
     */
    private String taskId;

    /**
     * 所属节点
     */
    private String nodeId;

    /**
     * 报文类型[ARTIFACT, OUTPUT]
     */
    private String payloadType;

    /**
     * 工具名
     */
    private String toolName;

    /**
     * 追踪号
     */
    private String traceId;

    /**
     * 报文内容
     */
    private String payloadData;

    /**
     * 删除标识[0:未删除, 1:已删除]
     */
    private Boolean deleteFlag;
}