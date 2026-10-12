package com.g2rain.agent.runtime.converter;

import com.g2rain.common.converter.CommonConverter;
import com.g2rain.agent.runtime.dao.po.ChatMessagePo;
import com.g2rain.agent.runtime.dto.ChatMessageDto;
import com.g2rain.agent.runtime.vo.ChatMessageVo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;

/**
 * 聊天消息表转换器
 * 用于Po、Vo、Dto之间的相互转换
 * 表名: chat_message
 *
 * @author G2rain Generator
 */
@Mapper(uses = CommonConverter.class)
public interface ChatMessageConverter {

    /**
     * 单例实例，通过 {@link Mappers#getMapper(Class)} 获取 MapStruct 自动生成的实现。
     */
    ChatMessageConverter INSTANCE = Mappers.getMapper(ChatMessageConverter.class);

    /**
     * Po -> Vo
     * 自动将 createTime 和 updateTime 从 {@link LocalDateTime} 转换为 {@link String}
     */
    @Mapping(target = "createTime", source = "createTime", qualifiedByName = "localDateTimeToString")
    @Mapping(target = "updateTime", source = "updateTime", qualifiedByName = "localDateTimeToString")
    ChatMessageVo po2vo(ChatMessagePo po);

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
    ChatMessagePo dto2po(ChatMessageDto dto);

    /**
     * Vo -> Dto（更新前从已有行带出字段，再由调用方补增量）
     */
    ChatMessageDto vo2dto(ChatMessageVo vo);
}
