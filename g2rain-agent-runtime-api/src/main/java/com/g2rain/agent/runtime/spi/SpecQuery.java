package com.g2rain.agent.runtime.spi;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 向 {@link SpecSource} 索取动作语义时的查询条件。
 *
 * @param skillKey  技能唯一键
 * @param toolNames 该技能可调用的业务工具名；来自 Studio 绑定
 * @param source    契约根属性 {@code source} 的原文；Runtime 不解释
 * @param pin       任务已锁定的版本标记；新任务为空，由实现方取当前活动版本
 * @author alpha
 * @since 2026/11/17
 */
public record SpecQuery(String skillKey, List<String> toolNames, JsonNode source, @Nullable String pin) {

    public SpecQuery {
        toolNames = List.copyOf(toolNames);
    }
}
