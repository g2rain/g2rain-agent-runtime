package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext.FingerprintDecision;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.engine.tools.ToolInterceptor;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCallback;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCategory;
import com.g2rain.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
     * @return TRIP 时返回熔断失败观测；其它情况为空表示放行
     */
    @Override
    public Optional<Observation> before(ToolInvokeRequest request) {
        if (request.toolCallback() instanceof BuiltinToolCallback b) {
            if (b.category() == BuiltinToolCategory.CONTROL || b.category() == BuiltinToolCategory.NODE) {
                return Optional.empty();
            }
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
            log.info("[Fingerprint] warn tool={} 再次相同将熔断", toolName);
            return Optional.empty();
        }

        if (decision == FingerprintDecision.TRIP) {
            String message = ModelHint.FINGERPRINT_TRIP.text("tool", toolName);
            NodeStatus nodeStatus = context.getNodeStatus();
            if (nodeStatus.isNonRunning()) {
                throw new BusinessException(RuntimeErrorCode.NODE_NOT_RUNNING, nodeStatus.name());
            }

            context.applyFail(message);

            return Optional.of(Observation.failure(message, RuntimeErrorCode.FINGERPRINT_TRIP.code()));
        }

        return Optional.empty();
    }

    /**
     * 调用成功后累加指纹计数。
     *
     * <p>控制类/节点类内置工具跳过。失败观测不改计数。</p>
     *
     * @param request 本次工具调用请求
     * @param result  工具观测
     * @return 原观测，不做改写
     */
    @Override
    public Observation after(ToolInvokeRequest request, Observation result) {
        if (request.toolCallback() instanceof BuiltinToolCallback b) {
            if (b.category() == BuiltinToolCategory.CONTROL || b.category() == BuiltinToolCategory.NODE) {
                return result;
            }
        }

        if (result.ok()) {
            request.context().recordFingerprint(request.fingerprint());
        }

        return result;
    }
}
