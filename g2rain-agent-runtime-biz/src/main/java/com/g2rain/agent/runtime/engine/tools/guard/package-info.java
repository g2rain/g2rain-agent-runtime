/**
 * Guard（工具门禁）包：工具调用前后的拦截与契约校验，拦住非法入参、补参义务、指纹熔断、响应嗅探与证据落库。
 * <p>
 * 各 {@link com.g2rain.agent.runtime.engine.tools.ToolInterceptor} 按 {@code @Order} 串联；
 * sniff / repair 辅助类型供契约与补参链路复用。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.engine.tools.guard;

import org.jspecify.annotations.NullMarked;
