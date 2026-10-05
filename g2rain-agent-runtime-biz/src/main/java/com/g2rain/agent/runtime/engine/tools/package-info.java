/**
 * Tools（工具调用）根包：节点可见工具表判别、统一调用入口、拦截器链、入参引用软解析、指纹与 Spring 桥接。
 * <p>
 * {@link com.g2rain.agent.runtime.engine.tools.ToolInvoker} 统一发起调用；
 * {@link com.g2rain.agent.runtime.engine.tools.ToolInterceptor} 在前后做门禁与观测改写。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.engine.tools;

import org.jspecify.annotations.NullMarked;
