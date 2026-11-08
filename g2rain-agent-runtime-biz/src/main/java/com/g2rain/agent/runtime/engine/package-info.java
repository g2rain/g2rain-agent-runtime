/**
 * 本轮（Turn）编排入口：请求、上下文、决策、结果与流水线契约。
 *
 * <p>一次用户请求对应一轮 Turn。入口类型在本包根下；状态门面、工具链、热态存储、
 * 模型出站与契约校验分属 {@code state}、{@code tools}、{@code store}、{@code model}、
 * {@code hydrate}。阶段实现在 {@code flow} 子包，由 {@link com.g2rain.agent.runtime.engine.TurnPipeline} 按序调用。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.engine;

import org.jspecify.annotations.NullMarked;
