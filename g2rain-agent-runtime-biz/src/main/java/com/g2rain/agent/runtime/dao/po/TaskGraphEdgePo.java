package com.g2rain.agent.runtime.dao.po;

import com.g2rain.common.model.BasePo;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


/**
 * 任务图依赖边表返回Po
 * 关联表名: task_graph_edge
 * 功能：封装实体数据，继承BasePo复用基础字段逻辑
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TaskGraphEdgePo extends BasePo {

    /**
     * 机构标识
     */
    private Long organId;

    /**
     * 所属任务
     */
    private String taskId;

    /**
     * 前置步骤
     */
    private String fromNodeId;

    /**
     * 后继步骤
     */
    private String toNodeId;

    /**
     * 边类型[NORMAL, DELEGATE]
     */
    private String edgeType;

    /**
     * 删除标识[0:未删除, 1:已删除]
     */
    private Boolean deleteFlag;
}