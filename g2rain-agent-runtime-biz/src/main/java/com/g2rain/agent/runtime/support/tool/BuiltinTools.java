package com.g2rain.agent.runtime.support.tool;


/**
 * 内置工具 Bean 的公共父类型。
 *
 * <p>实现类用 Spring AI {@code @Tool} 暴露方法，再由 {@link BuiltinToolCallbackProvider}
 * 收集并装饰成带类别与动作的回调。本身无方法，只作类型边界。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface BuiltinTools {

}
