package com.g2rain.agent.runtime.support;


import com.g2rain.agent.runtime.support.tool.BuiltinToolCallback;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 工具回调助手：判别内置工具，并按名在可见表中查找回调。
 *
 * <p>供节点循环与拦截器判断工具类型。本类不调用工具，也不改写回调表。</p>
 *
 * <pre>{@code
 * if (ToolCallbacks.isBuiltin(callback)) { ... }
 * ToolCallback found = ToolCallbacks.find(visible, "search_order");
 * }</pre>
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class ToolCallbacks {

    /**
     * 工具回调集合处理工具，负责按名称、类别和动作筛选运行时可用的工具回调；禁止实例化。
     */
    private ToolCallbacks() {

    }

    /**
     * 是否为内置工具回调（控制流、任务记忆、环境、会话等 Spring {@code @Tool}）。
     *
     * @param callback 工具回调；可空
     * @return 是否为 {@link BuiltinToolCallback}
     */
    public static boolean isBuiltin(@Nullable ToolCallback callback) {
        return callback instanceof BuiltinToolCallback;
    }

    /**
     * 在节点可见工具表中按名精确查找回调。
     *
     * <pre>{@code
     * ToolCallback cb = ToolCallbacks.find(table, toolName);
     * }</pre>
     *
     * @param table    可见工具表；可空
     * @param toolName 工具名；可空
     * @return 命中的回调；表空、名为空或未找到时返回 {@code null}
     */
    public static @Nullable ToolCallback find(@Nullable List<ToolCallback> table, @Nullable String toolName) {
        // 表空或名为空直接未命中，不扫回调
        if (Collections.isEmpty(table) || Strings.isBlank(toolName)) {
            return null;
        }

        return table.stream()
            .filter(o -> toolName.equals(o.getToolDefinition().name()))
            .findFirst()
            .orElse(null);
    }
}
