package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolCall;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.domain.tool.ToolResult;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.ToolErrors;
import com.g2rain.agent.runtime.engine.tools.ToolInterceptor;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Objects;

/**
 * 真实调用后把观测结算或登记为证据。
 *
 * <p>请求上已有 evidenceId 时走写工具 settle；否则按非写 record。内置工具不落证据。
 * 业务成败由上游 ResponseContract 写进 Observation，本类不再二次嗅探。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Order(7)
@Component
public class EvidenceInterceptor implements ToolInterceptor {

    private static final JsonCodec JSON = JsonCodecFactory.instance();

    private final RuntimeStateFacade stateFacade;

    /**
     * 创建证据结算拦截器。
     *
     * @param stateFacade 任务记忆读写门面，用于 settle / record
     */
    public EvidenceInterceptor(RuntimeStateFacade stateFacade) {
        this.stateFacade = stateFacade;
    }

    /**
     * 调用后把观测登记或结算为证据。
     *
     * <p>请求上已有 evidenceId 时走写工具 settle；否则按非写 record。内置工具不落证据。
     * 观测本身原样返回，业务成败由上游 ResponseContract 写入。</p>
     *
     * @param request 本次工具调用请求
     * @param result  工具观测
     * @return 原观测，不做改写
     */
    @Override
    public Observation after(ToolInvokeRequest request, Observation result) {
        if (ToolCallbacks.isBuiltin(request.toolCallback())) {
            return result;
        }

        ToolResult toolResult = toolResultOf(request, result);
        String evidenceId = request.evidenceId();
        if (Strings.isNotBlank(evidenceId)) {
            stateFacade.settle(request.context().scope(), evidenceId, toolResult);
        } else {
            JsonNode args = argsNode(request.arguments());
            ToolCall call = new ToolCall(request.toolName(), args, null, SideEffect.NONE, request.contract());
            stateFacade.record(request.context().scope(), call, toolResult);
        }

        return result;
    }

    /**
     * 只根据 Observation 推导证据，不再做业务判定。
     *
     * @param request     当前工具请求
     * @param observation 调用观测
     * @return 证据结算用的工具结果
     */
    private static ToolResult toolResultOf(ToolInvokeRequest request, Observation observation) {
        SideEffect sideEffect = sideEffectOf(request);
        EvidenceStatus status = ToolErrors.statusOf(observation, sideEffect);
        FailureKind kind;
        Boolean businessOk;
        if (observation.ok()) {
            kind = FailureKind.NONE;
            businessOk = SuccessRule.configured(request.contract()) ? Boolean.TRUE : null;
        } else if (ToolErrors.isCallFailure(observation)) {
            kind = ToolErrors.failureKindOf(observation.errorCode());
            businessOk = null;
        } else {
            kind = FailureKind.BUSINESS_FAILURE;
            businessOk = Boolean.FALSE;
        }

        Map<String, Integer> counts = Map.of();
        Boolean empty = null;
        String entities = null;
        if (!Objects.equals(Boolean.FALSE, businessOk)) {
            boolean callLayerOk = status == EvidenceStatus.SUCCESS;
            counts = EvidenceSemantics.extractCounts(request.contract(), observation.data());
            empty = EvidenceSemantics.empty(request.contract(), observation.data(), callLayerOk);
            entities = EvidenceSemantics.entitiesJson(request.contract(), observation.data(), callLayerOk);
        }

        return new ToolResult(
            status, observation.data(), businessOk, empty, counts, kind,
            observation.errorCode(), observation.message(), entities
        );
    }

    /**
     * 判定本次调用副作用：有预写证据 id 视为写，否则看契约。
     *
     * @param request 当前工具请求
     * @return 副作用等级；无契约为 {@link SideEffect#NONE}
     */
    private static SideEffect sideEffectOf(ToolInvokeRequest request) {
        if (Strings.isNotBlank(request.evidenceId())) {
            return SideEffect.WRITE;
        }

        if (Objects.nonNull(request.contract())) {
            return request.contract().sideEffectOrDefault();
        }

        return SideEffect.NONE;
    }

    /**
     * 入参文本转 JSON 对象。
     *
     * @param arguments 原始入参；空白按 {@code {}}
     * @return 非空 JSON 节点
     */
    private static JsonNode argsNode(String arguments) {
        JsonNode node = JSON.str2node(Strings.defaultIfBlank(arguments, "{}"));
        return Objects.isNull(node) ? JSON.str2node("{}") : node;
    }
}
