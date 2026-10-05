package com.g2rain.agent.runtime.domain.state.task;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 实体候选：证据 extracts[].entity 或 outputs.entity，persist 时并入会话记忆 {@link EntityEntry}。
 *
 * @param type        实体类型
 * @param id          业务 id
 * @param displayName 展示名；可空
 * @param attrs       标量属性
 * @author alpha
 * @since 2026/10/3
 */
public record EntitySeed(String type, String id, @Nullable String displayName, JsonNode attrs) {

}
