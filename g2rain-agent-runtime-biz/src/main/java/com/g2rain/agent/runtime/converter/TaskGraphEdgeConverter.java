package com.g2rain.agent.runtime.converter;

import com.g2rain.agent.runtime.domain.graph.TaskEdge;
import com.g2rain.common.converter.CommonConverter;
import com.g2rain.agent.runtime.dao.po.TaskGraphEdgePo;
import com.g2rain.agent.runtime.dto.TaskGraphEdgeDto;
import com.g2rain.agent.runtime.vo.TaskGraphEdgeVo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;

/**
 * 任务图依赖边表转换器
 * 用于Po、Vo、Dto之间的相互转换
 * 表名: task_graph_edge
 *
 * @author G2rain Generator
 */
@Mapper(uses = CommonConverter.class)
public interface TaskGraphEdgeConverter {

    /**
     * 单例实例，通过 {@link Mappers#getMapper(Class)} 获取 MapStruct 自动生成的实现。
     */
    TaskGraphEdgeConverter INSTANCE = Mappers.getMapper(TaskGraphEdgeConverter.class);

    /**
     * Po -> Vo
     * 自动将 createTime 和 updateTime 从 {@link LocalDateTime} 转换为 {@link String}
     */
    @Mapping(target = "createTime", source = "createTime", qualifiedByName = "localDateTimeToString")
    @Mapping(target = "updateTime", source = "updateTime", qualifiedByName = "localDateTimeToString")
    TaskGraphEdgeVo po2vo(TaskGraphEdgePo po);

    /**
     * Dto -> Po
     * 自动将 createTime 和 updateTime 从 {@link String} 转换为 {@link LocalDateTime}
     * 忽略 version 字段
     * 忽略 deleteFlag 字段
     */
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "deleteFlag", ignore = true)
    @Mapping(target = "createTime", source = "createTime", qualifiedByName = "stringToLocalDateTime")
    @Mapping(target = "updateTime", source = "updateTime", qualifiedByName = "stringToLocalDateTime")
    TaskGraphEdgePo dto2po(TaskGraphEdgeDto dto);

    /**
     * Vo -> Dto（更新前从已有行带出字段，再由调用方补增量）
     */
    TaskGraphEdgeDto vo2dto(TaskGraphEdgeVo vo);

    /**
     * 领域边 -> Dto；taskId / organId 等由调用方补。
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "organId", ignore = true)
    @Mapping(target = "taskId", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    @Mapping(target = "fromNodeId", source = "from")
    @Mapping(target = "toNodeId", source = "to")
    @Mapping(target = "edgeType", source = "type")
    TaskGraphEdgeDto edge2dto(TaskEdge edge);
}
