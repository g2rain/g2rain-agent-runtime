package com.g2rain.agent.runtime.converter;

import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.common.converter.CommonConverter;
import com.g2rain.agent.runtime.dao.po.TaskGraphNodePo;
import com.g2rain.agent.runtime.dto.TaskGraphNodeDto;
import com.g2rain.agent.runtime.vo.TaskGraphNodeVo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;

/**
 * 任务图步骤表转换器
 * 用于Po、Vo、Dto之间的相互转换
 * 表名: task_graph_node
 *
 * @author G2rain Generator
 */
@Mapper(uses = CommonConverter.class)
public interface TaskGraphNodeConverter {

    /**
     * 单例实例，通过 {@link Mappers#getMapper(Class)} 获取 MapStruct 自动生成的实现。
     */
    TaskGraphNodeConverter INSTANCE = Mappers.getMapper(TaskGraphNodeConverter.class);

    /**
     * Po -> Vo
     * 自动将 createTime 和 updateTime 从 {@link LocalDateTime} 转换为 {@link String}
     */
    @Mapping(target = "createTime", source = "createTime", qualifiedByName = "localDateTimeToString")
    @Mapping(target = "updateTime", source = "updateTime", qualifiedByName = "localDateTimeToString")
    TaskGraphNodeVo po2vo(TaskGraphNodePo po);

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
    TaskGraphNodePo dto2po(TaskGraphNodeDto dto);

    /**
     * Vo -> Dto（更新前从已有行带出字段，再由调用方补增量）
     */
    TaskGraphNodeDto vo2dto(TaskGraphNodeVo vo);

    /**
     * 领域节点 -> Dto；taskId / organId 等由调用方补。
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "organId", ignore = true)
    @Mapping(target = "taskId", ignore = true)
    @Mapping(target = "startedTime", ignore = true)
    @Mapping(target = "finishedTime", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    @Mapping(target = "state", ignore = true)
    @Mapping(target = "status", source = "status")
    TaskGraphNodeDto node2dto(TaskNode node);
}
