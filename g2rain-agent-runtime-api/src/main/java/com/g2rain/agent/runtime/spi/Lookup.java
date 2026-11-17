package com.g2rain.agent.runtime.spi;

import tools.jackson.databind.JsonNode;

/**
 * 一次已成功的读工具调用（不论结果是否为空）。
 *
 * @param toolName  工具注册名
 * @param arguments 入参（通常来自证据头脱敏摘要）
 * @author alpha
 * @since 2026/11/17
 */
public record Lookup(String toolName, JsonNode arguments) {

}
