package com.g2rain.agent.runtime.engine.tools;


import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;

import java.util.Optional;

/**
 * 工具调用拦截器。
 *
 * <p>{@link #before(ToolInvokeRequest)} 用于调用前校验、参数改写和短路；
 * {@link #after(ToolInvokeRequest, Observation)} 用于处理工具结果。拦截器链的顺序由
 * 实现方决定，after 通常按调用前顺序的逆序执行。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface ToolInterceptor {

    /**
     * 在实际工具调用前执行检查或参数准备。
     *
     * @param request 本次调用请求
     * @return 阻断观测；返回 {@link Optional#empty()} 表示允许继续调用
     */
    default Optional<Observation> before(ToolInvokeRequest request) {
        return Optional.empty();
    }

    /**
     * 在工具调用或前置短路完成后处理观测结果。
     *
     * @param request 本次调用请求
     * @param result  真实调用或前置短路后的观测
     * @return 传递给下一个拦截器或调用方的观测
     */
    default Observation after(ToolInvokeRequest request, Observation result) {
        return result;
    }
}
