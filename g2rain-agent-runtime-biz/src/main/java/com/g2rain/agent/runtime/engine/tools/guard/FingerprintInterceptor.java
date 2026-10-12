package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.Outcome;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext.FingerprintDecision;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.engine.tools.ToolInterceptor;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCategory;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.exception.BusinessException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 指纹门：同一工具同入参连续成功时预警或熔断。
 *
 * <p>指纹含工具名与规范化入参，只统计连续相同指纹。{@code before} 预览决策做门禁不改计数；
 * {@code after} 在调用成功后再累加。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Order(4)
@Component
public class FingerprintInterceptor implements ToolInterceptor {

    /**
     * 指纹阈值配置（连续相同成功次数）。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * @param runtimeProperties {@code react.fingerprint-trip-limit}
     */
    public FingerprintInterceptor(RuntimeProperties runtimeProperties) {
        this.runtimeProperties = runtimeProperties;
    }

    /**
     * 调用前只预览指纹决策：WARN 放行并打日志，TRIP 短路熔断。不改计数。
     *
     * <p>控制类/节点类内置工具与用户已确认的写工具跳过。节点非运行中遇 TRIP 抛业务异常。</p>
     *
     * @param request 本次工具调用请求
     * @return TRIP 时返回熔断拒绝观测；其它情况为空表示放行
     */
    @Override
    public Optional<Observation> before(ToolInvokeRequest request) {
        if (exempt(request)) {
            return Optional.empty();
        }

        if (request.context().approvedWrite()) {
            // 用户已确认的写工具不走指纹熔断
            return Optional.empty();
        }

        String toolName = request.toolName();
        ToolExecutionContext context = request.context();
        int limit = runtimeProperties.getReact().getFingerprintTripLimit();
        FingerprintDecision decision = context.peekFingerprintDecision(request.fingerprint(), limit);
        if (decision == FingerprintDecision.WARN) {
            Trace.at(TraceStage.TOOL, "repeat_warned").kv("tool", toolName).warn();
            return Optional.empty();
        }

        if (decision == FingerprintDecision.TRIP) {
            String message = ModelHint.FINGERPRINT_TRIP.text("tool", toolName);
            NodeStatus nodeStatus = context.getNodeStatus();
            if (nodeStatus.isNonRunning()) {
                throw new BusinessException(RuntimeErrorCode.NODE_NOT_RUNNING, nodeStatus.name());
            }

            context.applyFail(message);

            return Optional.of(Observation.rejected(RuntimeErrorCode.FINGERPRINT_TRIP, message, ModelHint.NEXT_STOPPED.text()));
        }

        return Optional.empty();
    }

    /**
     * 调用成功后累加指纹计数。
     *
     * <p>控制类/节点类内置工具跳过。非成功观测不改计数。</p>
     *
     * @param request 本次工具调用请求
     * @param result  工具观测
     * @return 原观测，不做改写
     */
    @Override
    public Observation after(ToolInvokeRequest request, Observation result) {
        if (exempt(request)) {
            return result;
        }

        if (result.outcome() == Outcome.SUCCESS) {
            request.context().recordFingerprint(request.fingerprint());
        }

        return result;
    }

    /**
     * 控流类与节点类内置工具不参与指纹熔断。
     *
     * @param request 本次工具调用请求
     * @return 免于熔断时为 {@code true}
     */
    private static boolean exempt(ToolInvokeRequest request) {
        return ToolCallbacks.is(request.toolCallback(), BuiltinToolCategory.CONTROL)
            || ToolCallbacks.is(request.toolCallback(), BuiltinToolCategory.NODE);
    }
}
