package com.g2rain.agent.runtime.domain.tool;


import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 一次待执行工具调用的不可变描述。
 *
 * <p>对象把工具名、业务参数、副作用等级和契约绑定在一起，供预写校验、幂等计算和实际
 * 调用共同使用。未声明业务幂等键时，调用方应根据规范化后的参数计算兜底指纹。</p>
 *
 * @param toolName       工具名
 * @param args           业务入参
 * @param idempotencyKey 契约幂等键；未配置为空，由 argsHash 兜底
 * @param sideEffect     NONE / WRITE
 * @param contract       工具契约；用于 argsBrief 脱敏；可空
 * @author alpha
 * @since 2026/10/3
 */
public record ToolCall(String toolName, JsonNode args, @Nullable String idempotencyKey, SideEffect sideEffect,
                       @Nullable ToolSpec contract) {

    /**
     * 创建不带工具契约的调用描述，适用于内置控制工具或测试路径。
     *
     * @param toolName       工具名
     * @param args           工具参数
     * @param idempotencyKey 业务幂等键；可空
     * @param sideEffect     副作用等级
     */
    public ToolCall(String toolName, JsonNode args, @Nullable String idempotencyKey, SideEffect sideEffect) {
        this(toolName, args, idempotencyKey, sideEffect, null);
    }
}
