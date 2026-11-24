package com.g2rain.agent.runtime.spi;

import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 能力工具的说明：名称、展示名、给模型的描述与入参 Schema。
 *
 * @param name        工具名；小写字母、数字、下划线，2–64 位；不得以 {@code builtin_}、{@code native_} 开头
 * @param label       展示名；按语言取值，缺省取 {@code zh-CN}
 * @param description 给模型的说明
 * @param inputSchema 入参的 JSON Schema
 * @param external    结果是否为外部内容
 * @author alpha
 * @since 2026/11/24
 */
public record CapabilitySpec(String name, Map<String, String> label, String description, JsonNode inputSchema,
                             boolean external) {

    public CapabilitySpec {
        label = Map.copyOf(label);
    }
}
