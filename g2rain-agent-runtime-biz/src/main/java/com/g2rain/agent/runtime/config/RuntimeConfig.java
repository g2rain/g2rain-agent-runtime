package com.g2rain.agent.runtime.config;


import com.g2rain.agent.runtime.support.tool.BuiltinToolCallbackProvider;
import com.g2rain.agent.runtime.support.tool.BuiltinTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * RuntimeConfig：Runtime 通用 Bean（如内置工具回调提供者）。
 * <p>
 * Turn（本轮）= 一次用户请求从进入到产出结果的完整处理。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Configuration
public class RuntimeConfig {

    /**
     * 把全部 BuiltinTools 收成 Spring AI ToolCallback 提供者。
     *
     * @param tools 内置工具实现列表
     * @return 回调提供者
     */
    @Bean
    BuiltinToolCallbackProvider builtinToolCallbackProvider(List<BuiltinTools> tools) {
        // 扫描全部 BuiltinTools Bean，装饰为带 category/action 的 ToolCallback
        return new BuiltinToolCallbackProvider(tools);
    }
}
