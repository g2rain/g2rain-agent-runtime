package com.g2rain.agent.runtime.spi;

import tools.jackson.databind.JsonNode;

/**
 * 一次能力工具调用的入参与现场。
 *
 * @param arguments 入参（已按 JSON 解析，未做 Schema 校验）
 * @param context   调用现场
 * @author alpha
 * @since 2026/11/24
 */
public record CapabilityCall(JsonNode arguments, CapabilityContext context) {

}
