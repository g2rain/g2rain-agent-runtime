package com.g2rain.agent.runtime.engine.model;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContext;
import com.g2rain.common.web.PrincipalContextHolder;
import com.g2rain.model.nexus.dto.ChatCompletionRequest;
import com.g2rain.model.nexus.dto.EmbeddingsRequest;
import com.g2rain.model.nexus.vo.ChatCompletionResponse;
import com.g2rain.model.nexus.vo.EmbeddingsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * DefaultModelGateway（默认模型网关）：经 {@link RestClient} 把请求 POST 到模型服务。
 * <p>
 * 路径：聊天 {@code /v1/chat/completions}，向量 {@code /v1/embeddings}。
 * 出站时透传当前 Principal 请求头（如租户、TRACE_ID）；对网络失败与 5xx 按配置重试，
 * 耗尽或非 5xx / 空 body 时抛 {@link BusinessException}（{@link RuntimeErrorCode#DEPENDENCY_UNAVAILABLE}）。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultModelGateway implements ModelGateway {

    /**
     * 指向模型服务的 RestClient（已配 baseUrl 等）。
     */
    private final RestClient modelRestClient;

    /**
     * Runtime 配置；其中 model.maxRetries 控制额外重试次数。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 调用聊天补全接口。
     *
     * @param request 聊天补全请求
     * @return 聊天补全响应
     */
    @Override
    public ChatCompletionResponse chat(ChatCompletionRequest request) {
        // POST /v1/chat/completions
        return exchange("/v1/chat/completions", request, ChatCompletionResponse.class);
    }

    /**
     * 调用向量化接口。
     *
     * @param request 向量化请求
     * @return 向量化响应
     */
    @Override
    public EmbeddingsResponse embed(EmbeddingsRequest request) {
        // POST /v1/embeddings
        return exchange("/v1/embeddings", request, EmbeddingsResponse.class);
    }

    /**
     * 带重试的 JSON POST：透传 Principal 头，解析响应体。
     * <p>
     * 总尝试次数 = 1 + max(0, maxRetries)。
     * {@link ResourceAccessException} 与 5xx 可重试；其它 HTTP 错误立即包装抛出；
     * 全部失败后以最后一次访问错误为 cause 抛依赖不可用。
     * </p>
     *
     * @param path         相对路径，如 {@code /v1/chat/completions}
     * @param body         请求体
     * @param responseType 响应类型
     * @param <T>          响应类型参数
     * @return 非空响应体
     */
    private <T> T exchange(String path, Object body, Class<T> responseType) {
        // 记录最后一次可重试失败，耗尽后作为 cause
        RuntimeException lastAccessError = null;
        RuntimeProperties.Model cfg = runtimeProperties.getModel();
        // 至少试 1 次，再加配置的额外重试次数
        for (int i = 0, j = 1 + Math.max(0, cfg.getMaxRetries()); i < j; i++) {
            try {
                // 组装 POST + JSON
                RestClient.RequestBodySpec spec = modelRestClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);

                // 透传当前 Principal 出站头（租户 / TRACE_ID 等），无上下文则不带头
                Optional.ofNullable(PrincipalContextHolder.get())
                    .map(PrincipalContext::getHeaders)
                    .orElseGet(Map::of)
                    .forEach((name, values) -> Optional
                        .ofNullable(values)
                        .stream()
                        .flatMap(Collection::stream)
                        .filter(Strings::isNotBlank)
                        .forEach(v -> spec.header(name, v))
                    );

                // 发送并反序列化响应
                T response = spec.body(body).retrieve().body(responseType);
                if (Objects.isNull(response)) {
                    // 200 但 body 空：当依赖失败，避免 Loop 拿到 null 继续编
                    throw new BusinessException(
                        RuntimeErrorCode.DEPENDENCY_UNAVAILABLE,
                        "Model Gateway empty body"
                    );
                }
                return response;
            } catch (ResourceAccessException ex) {
                // 网络/连接类失败：记下，后续重试
                lastAccessError = ex;
                log.warn(
                    "Model Nexus {} access failed, will retry ({}/{}): {}",
                    path, i + 1, j - 1, ex.getMessage()
                );
            } catch (RestClientResponseException ex) {
                // 非 5xx：业务/客户端错误，不重试，直接包装抛出
                if (!ex.getStatusCode().is5xxServerError()) {
                    throw new BusinessException(
                        RuntimeErrorCode.DEPENDENCY_UNAVAILABLE,
                        ex, "Model Gateway"
                    );
                }
                // 5xx：可重试
                lastAccessError = ex;
                log.warn(
                    "Model Nexus {} 5xx, will retry ({}/{}): {}",
                    path, i + 1, j - 1, ex.getMessage()
                );
            }
        }

        // 循环正常结束却无 cause：补一个耗尽说明
        if (Objects.isNull(lastAccessError)) {
            lastAccessError = new IllegalStateException("retries exhausted");
        }

        // 重试耗尽：依赖不可用
        throw new BusinessException(
            RuntimeErrorCode.DEPENDENCY_UNAVAILABLE,
            lastAccessError, "Model Gateway"
        );
    }
}
