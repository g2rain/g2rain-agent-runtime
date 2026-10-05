package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.hydrate.ResponseContract;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.engine.tools.ToolInterceptor;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCallback;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Objects;


/**
 * 调用后嗅探业务成败，失败则改写观测。
 *
 * <p>有 {@code successCondition} 时按条件判断；否则尝试顶层 {@code status} HTTP 码。
 * {@code errorPath} 只在业务失败时抽错误码和文案。仅处理业务工具且调用层 {@code ok=true} 的观测；
 * 内置工具与传输层失败跳过。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Order(8)
@Component
public class ResponseContractInterceptor implements ToolInterceptor {

    /**
     * JSON 编解码器。
     * <p>
     * 用于响应体字段路径查询、节点判空、节点转字符串以及响应体序列化等操作。
     * </p>
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 错误文案截断上限。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * @param runtimeProperties 观察错误文案上限
     */
    public ResponseContractInterceptor(RuntimeProperties runtimeProperties) {
        this.runtimeProperties = runtimeProperties;
    }

    /**
     * 调用后嗅探业务结果。
     * <p>
     * 首先过滤不需要嗅探的场景，然后根据工具响应契约判断业务成败。
     * 当工具本身调用成功，但响应内容明确表示业务失败时，将原成功观察改写为失败观察。
     *
     * <p>
     * 成功条件存在时：
     * <ul>
     *     <li>成功条件满足：保持原观察。</li>
     *     <li>成功条件不满足：判定业务失败，并通过 {@code errorPath} 提取错误信息。</li>
     * </ul>
     *
     * <p>
     * 未配置成功条件时，则尝试使用顶层 {@code status} 按 HTTP 2xx 规则进行兜底判断。
     * </p>
     *
     * @param request 本次工具调用请求
     * @param result  工具调用产生的原始观察结果
     * @return 原观察结果，或改写后的业务失败观察
     */
    @Override
    public Observation after(ToolInvokeRequest request, Observation result) {
        if (ToolCallbacks.isBuiltin(request.toolCallback())) {
            return result;
        }

        if (!result.ok()) {
            return result;
        }

        if (!SuccessRule.configured(request.contract())) {
            return result;
        }

        JsonNode body = result.data();
        Boolean businessOk = SuccessRule.businessOk(request.contract(), body);
        if (!Objects.equals(Boolean.FALSE, businessOk)) {
            return result;
        }

        ResponseContract.ErrorPath errorPath = errorPathOf(request.contract());
        String code = Objects.nonNull(body) ? extractErrorCode(body, errorPath) : null;
        if (Strings.isBlank(code)) {
            code = RuntimeErrorCode.TOOL_BUSINESS_FAILED.code();
        }

        String msg = Objects.nonNull(body) ? extractErrorMessage(body, errorPath) : ModelHint.BUSINESS_EMPTY_BODY.text();
        // 保留 data，供外层 Evidence / extracts 使用；表外错误码表示业务失败
        return new Observation(false, msg, body, code);
    }

    /**
     * 工具契约上的 error 路径；未配置返回 null。
     *
     * @param contract 工具契约；可空
     * @return 错误字段路径；未配置为 {@code null}
     */
    private static ResponseContract.@Nullable ErrorPath errorPathOf(@Nullable ToolSpec contract) {
        if (Objects.isNull(contract) || Objects.isNull(contract.getError())) {
            return null;
        }

        String codePath = contract.getError().getCode();
        String messagePath = contract.getError().getMessage();
        if (Strings.isBlank(codePath) && Strings.isBlank(messagePath)) {
            return null;
        }

        return new ResponseContract.ErrorPath(codePath, messagePath);
    }

    /**
     * 从响应体中提取业务错误编码。
     * <p>
     * 优先使用工具契约配置的 {@code errorCodePath}；
     * 未配置或指定路径没有值时，再回退到通用 {@code errorCode} 字段。
     *
     * @param body 工具响应体
     * @param ep   错误字段路径；允许为空
     * @return 错误编码
     */
    private String extractErrorCode(JsonNode body, ResponseContract.@Nullable ErrorPath ep) {
        if (Objects.nonNull(ep) && Strings.isNotBlank(ep.codePath())) {
            JsonNode val = JSON.lookupNode(body, ep.codePath());
            if (JSON.hasValue(val)) {
                return JSON.asString(val);
            }
        }
        return RuntimeErrorCode.TOOL_BUSINESS_FAILED.code();
    }

    /**
     * 从响应体中提取业务错误文案。
     * <p>
     * 优先使用工具契约配置的 {@code errorMessagePath}；
     * 未配置或指定路径没有值时，再回退到通用 {@code errorMessage} 字段。
     *
     * @param body 工具响应体
     * @param ep   错误字段路径；允许为空
     * @return 错误文案
     */
    private String extractErrorMessage(JsonNode body, ResponseContract.@Nullable ErrorPath ep) {
        if (Objects.nonNull(ep) && Strings.isNotBlank(ep.messagePath())) {
            JsonNode val = JSON.lookupNode(body, ep.messagePath());
            if (JSON.hasValue(val)) {
                return truncate(JSON.asString(val));
            }
        }
        return truncate(JSON.obj2str(body));
    }

    /**
     * 截断过长的错误信息。
     * <p>
     * 防止将完整的大型业务响应体直接写入 Observation，
     * 从而避免错误信息过大影响后续日志、证据或模型上下文。
     *
     * @param s 原始文本
     * @return 不超过配置上限的文本
     */
    private String truncate(String s) {
        int max = runtimeProperties.getReact().getObservationErrorMaxChars();
        if (s.length() <= max) {
            return s;
        }

        return s.substring(0, max) + "...";
    }
}
