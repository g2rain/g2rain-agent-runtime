package com.g2rain.agent.runtime.support.tool;


import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把全部 {@link BuiltinTools} Bean 转成带元数据的 ToolCallback 数组。
 *
 * <p>用 Spring AI {@link ToolCallbacks#from} 生成原始回调，再扫描 {@code @Tool} 与
 * {@link BuiltinToolKind} 包成 {@link BuiltinToolCallback}。数组在构造时算好，之后只读。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public class BuiltinToolCallbackProvider implements ToolCallbackProvider {

    /**
     * 装饰后的工具回调数组（含 category/action），构造时一次性算好。
     */
    private final ToolCallback[] toolCallbacks;

    /**
     * 收集内置工具 Bean，扫描种类注解并装饰回调。
     *
     * @param tools 全部 BuiltinTools 实现（Spring 注入的 Bean 列表）
     */
    public BuiltinToolCallbackProvider(List<BuiltinTools> tools) {
        ToolCallback[] callbacks = ToolCallbacks.from(tools.toArray());

        Map<String, BuiltinToolKind> kinds = new HashMap<>();
        for (BuiltinTools bean : tools) {
            Class<?> type = ClassUtils.getUserClass(bean);
            for (Method method : type.getMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (Objects.isNull(tool)) {
                    continue;
                }

                var kind = method.getAnnotation(
                    BuiltinToolKind.class
                );

                // 未标种类的 @Tool 仍会进回调数组，只是 category/action 为空
                if (Objects.isNull(kind)) {
                    continue;
                }

                String name = tool.name();
                // @Tool.name 空白时退回方法名，与 Spring AI 注册键对齐
                name = name.isBlank() ? method.getName() : name;

                kinds.put(name, kind);
            }
        }

        this.toolCallbacks = Arrays.stream(callbacks)
            .map(callback -> decorate(callback, kinds))
            .toArray(ToolCallback[]::new);
    }

    /**
     * 用查到的 {@link BuiltinToolKind} 装饰单个回调；查不到则 kind 为 null。
     *
     * @param callback 原始 ToolCallback
     * @param kinds    工具名 → 种类表
     * @return 带 category/action 的 BuiltinToolCallback
     */
    private BuiltinToolCallback decorate(ToolCallback callback, Map<String, BuiltinToolKind> kinds) {
        return new BuiltinToolCallback(callback, kinds.get(callback.getToolDefinition().name()));
    }

    /**
     * 返回构造期已装饰好的全部内置工具回调。
     *
     * @return ToolCallback 数组（元素实际为 BuiltinToolCallback）
     */
    @Override
    public ToolCallback[] getToolCallbacks() {
        return this.toolCallbacks;
    }
}
