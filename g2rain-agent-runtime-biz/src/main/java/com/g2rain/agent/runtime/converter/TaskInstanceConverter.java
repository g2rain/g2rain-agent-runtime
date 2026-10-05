package com.g2rain.agent.runtime.converter;

import com.g2rain.common.converter.CommonConverter;
import com.g2rain.agent.runtime.dao.po.TaskInstancePo;
import com.g2rain.agent.runtime.dto.TaskInstanceDto;
import com.g2rain.agent.runtime.vo.TaskInstanceVo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;

/**
 * 任务实例表转换器
 * 用于Po、Vo、Dto之间的相互转换
 * 表名: task_instance
 *
 * @author G2rain Generator
 */
@Mapper(uses = CommonConverter.class)
public interface TaskInstanceConverter {

    /**
     * 单例实例，通过 {@link Mappers#getMapper(Class)} 获取 MapStruct 自动生成的实现。
     */
    TaskInstanceConverter INSTANCE = Mappers.getMapper(TaskInstanceConverter.class);

    /**
     * Po -> Vo
     * 自动将 createTime 和 updateTime 从 {@link LocalDateTime} 转换为 {@link String}
     */
    @Mapping(target = "createTime", source = "createTime", qualifiedByName = "localDateTimeToString")
    @Mapping(target = "updateTime", source = "updateTime", qualifiedByName = "localDateTimeToString")
    TaskInstanceVo po2vo(TaskInstancePo po);

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
    TaskInstancePo dto2po(TaskInstanceDto dto);

    /**
     * Vo -> Dto（更新前从已有行带出字段，再由调用方补增量）
     */
    TaskInstanceDto vo2dto(TaskInstanceVo vo);
}
