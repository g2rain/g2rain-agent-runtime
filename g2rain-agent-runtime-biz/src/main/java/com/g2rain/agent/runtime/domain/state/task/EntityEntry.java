package com.g2rain.agent.runtime.domain.state.task;


import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 会话记忆中的一条实体索引（Hash field = {@code type:id}）。
 *
 * <p>保存已确认、可跨 Task 继承的结构化实体：展示名、属性、最近任务与来源证据。
 * persist 时由 {@link EntitySeed} 并入；任务前情按指代匹配读取，运行期不在此回写业务值。</p>
 *
 * @param displayName 展示名
 * @param attrs       属性 JSON
 * @param taskId      最近出现的任务；可空
 * @param evidenceId  来源证据；可空
 * @param seenAt      最近见到时间（毫秒）
 * @param mentions    出现次数
 * @author alpha
 * @since 2026/10/3
 */
public record EntityEntry(String displayName, JsonNode attrs, @Nullable String taskId, @Nullable String evidenceId,
                          long seenAt, int mentions) {

}
