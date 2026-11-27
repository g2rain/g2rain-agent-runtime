package com.g2rain.agent.runtime.spi;

import tools.jackson.databind.JsonNode;

/**
 * 能力工具的说明：名称、展示名、给模型的描述与入参 Schema。
 *
 * @param name        工具名；必须以 {@code builtin_} 开头，后接小写字母、数字、下划线，总长不超过 64
 * @param label       中文展示名，作多语言文案缺失时的兜底
 * @param description 给模型的说明
 * @param inputSchema 入参的 JSON Schema
 * @param external    结果是否为外部内容
 * @param kind        工具种类；决定执行步骤的展示图标
 * @author alpha
 * @since 2026/11/24
 */
public record CapabilitySpec(String name, String label, String description, JsonNode inputSchema,
                             boolean external, ToolKind kind) {

}
