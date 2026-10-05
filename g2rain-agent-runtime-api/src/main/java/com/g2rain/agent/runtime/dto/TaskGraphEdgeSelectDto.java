package com.g2rain.agent.runtime.dto;

import com.g2rain.common.model.BaseSelectListDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


/**
 * 任务图依赖边表查询入参DTO
 * 用于TaskGraphEdgeDao.selectList方法的条件筛选
 * 表名: task_graph_edge
 *
 * @author G2rain Generator
 */
@Setter
@Getter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "任务图依赖边表查询入参 DTO")
public class TaskGraphEdgeSelectDto extends BaseSelectListDto {

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
     * 前置步骤
     */
    @Schema(description = "前置步骤")
    private String fromNodeId;

    /**
     * 后继步骤
     */
    @Schema(description = "后继步骤")
    private String toNodeId;

    /**
     * 边类型[NORMAL, DELEGATE]
     */
    @Schema(description = "边类型[NORMAL, DELEGATE]")
    private String edgeType;
}
