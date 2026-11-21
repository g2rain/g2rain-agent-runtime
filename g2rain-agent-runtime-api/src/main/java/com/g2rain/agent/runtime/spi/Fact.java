package com.g2rain.agent.runtime.spi;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 一次成功调用结果中按抽取项取出的原文，供 {@link ToolGuard} 做前置条件判定。
 *
 * @param key   抽取键；可空
 * @param value 抽取路径上的原值
 * @author alpha
 * @since 2026/11/17
 */
public record Fact(@Nullable String key, JsonNode value) {

}
