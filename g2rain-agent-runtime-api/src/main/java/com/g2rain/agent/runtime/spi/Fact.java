package com.g2rain.agent.runtime.spi;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 一条成功证据的抽取内容，供 {@link ToolGuard} 做前置条件判定。
 *
 * @param toolName 产出该证据的工具名
 * @param key      抽取键；可空
 * @param value    抽取路径上的原值
 * @author alpha
 * @since 2026/11/17
 */
public record Fact(String toolName, @Nullable String key, JsonNode value) {

}
