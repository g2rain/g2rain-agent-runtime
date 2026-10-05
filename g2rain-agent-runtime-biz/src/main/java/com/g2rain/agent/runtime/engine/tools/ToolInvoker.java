package com.g2rain.agent.runtime.engine.tools;


import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;

/**
 * 工具调用入口。
 *
 * <p>实现负责协调调用前校验、实际工具调用和调用后结果处理，并把所有分支统一表示为
 * {@link Observation}。调用方不应绕过该接口直接调用工具回调。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface ToolInvoker {

    /**
     * 执行一次工具调用。
     *
     * <p>拦截器可以在实际调用前返回阻断观测；此时工具不会被执行。</p>
     *
     * @param request 工具名、回调、入参与执行上下文
     * @return 成功、失败或被拦截的观测结果
     */
    Observation invoke(ToolInvokeRequest request);
}
