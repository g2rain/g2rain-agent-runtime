package com.g2rain.agent.runtime.engine.model;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.state.progress.StepStage;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import com.g2rain.agent.runtime.event.StepEvents;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceEvent;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContext;
import com.g2rain.common.web.PrincipalContextHolder;
import com.g2rain.model.nexus.dto.ChatCompletionRequest;
import com.g2rain.model.nexus.dto.EmbeddingsRequest;
import com.g2rain.model.nexus.schema.ChatCompletionChoice;
import com.g2rain.model.nexus.schema.ChatCompletionUsage;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.schema.ChatToolCall;
import com.g2rain.model.nexus.vo.ChatCompletionResponse;
import com.g2rain.model.nexus.vo.EmbeddingsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
     * 完整内容输出时序列化请求与响应。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 表示额度不足的厂商错误码（OpenAI / Anthropic）。
     */
    private static final Set<String> QUOTA_CODES = Set.of("insufficient_quota", "billing_error");

    /**
     * 指向模型服务的 RestClient（已配 baseUrl 等）。
     */
    private final RestClient modelRestClient;

    /**
     * Runtime 配置；其中 model.maxRetries 控制额外重试次数。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 执行步骤的记录与推送。
     */
    private final StepEvents stepEvents;

    /**
     * 调用聊天补全接口，并输出 {@code [Trace][MODEL]} 调用轨迹。
     *
     * <p>每次调用输出一行摘要；命中异常判定（空响应、输出被截断、调用异常）时以 WARN 输出，
     * 并按 {@link Trace#modelPayload()} 决定是否追加完整请求与响应。</p>
     *
     * @param use     调用用途
     * @param model   本轮选用的模型名；为空时由模型服务按默认路由
     * @param request 聊天补全请求
     * @return 聊天补全响应
     */
    @Override
    public ChatCompletionResponse chat(ModelUse use, @Nullable String model, ChatCompletionRequest request) {
        long start = System.nanoTime();
        // 模型步骤只显示用途与耗时，不显示模型名与 token 用量
        String stepId = stepEvents.start(StepStage.MODEL, RuntimeMessage.valueOf("MODEL_" + use.name()).text());
        // 本轮指定了模型才写入，未指定保持请求原值
        if (Strings.isNotBlank(model)) {
            request.setModel(model.trim());
        }
        try {
            // POST /v1/chat/completions
            var rsp = exchange("/v1/chat/completions", request, ChatCompletionResponse.class);
            traceCall(use, request, rsp, elapsedMs(start));
            stepEvents.done(stepId, null, usageOf(use, rsp));
            return rsp;
        } catch (RuntimeException ex) {
            traceFailure(use, request, ex, elapsedMs(start));
            stepEvents.fail(stepId, ex);
            throw ex;
        }
    }

    /**
     * 模型步骤的排查信息：用途、实际模型与 token 明细；只入库，不下发。
     *
     * @param use      调用用途
     * @param response 模型响应
     * @return 排查信息
     */
    private static Map<String, Object> usageOf(ModelUse use, ChatCompletionResponse response) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("use", use.name());
        if (Strings.isNotBlank(response.getModel())) {
            extra.put("model", response.getModel());
        }
        ChatCompletionUsage usage = response.getUsage();
        if (Objects.nonNull(usage)) {
            Optional.ofNullable(usage.getPromptTokens()).ifPresent(v -> extra.put("promptTokens", v));
            Optional.ofNullable(usage.getCompletionTokens()).ifPresent(v -> extra.put("completionTokens", v));
            Optional.ofNullable(usage.getTotalTokens()).ifPresent(v -> extra.put("totalTokens", v));
        }
        return extra;
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
                // 4xx 与超时：重试也无法恢复，按上游状态码与错误码分类后直接抛出
                RuntimeErrorCode classified = classify(ex);
                if (Objects.nonNull(classified)) {
                    throw new BusinessException(classified, ex, upstreamMessage(ex));
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

    /**
     * 按 model-nexus 透传的上游状态码与错误码分类；可重试的 5xx（不含 504 与 invalid_response）返回 {@code null}。
     *
     * @param ex 模型服务的错误响应
     * @return 模型类错误码；可重试时为空
     */
    private static @Nullable RuntimeErrorCode classify(RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        String code = Strings.defaultIfBlank(Objects.toString(upstreamError(ex).get("code"), null), "");
        if (status == 402 || QUOTA_CODES.contains(code)) {
            return RuntimeErrorCode.MODEL_QUOTA;
        }
        if (status == 401 || status == 403) {
            return RuntimeErrorCode.MODEL_AUTH;
        }
        if (status == 429) {
            return RuntimeErrorCode.MODEL_RATE_LIMIT;
        }
        if (status == 504 || "timeout".equals(code)) {
            return RuntimeErrorCode.MODEL_TIMEOUT;
        }
        // 上游返回格式不合规：同一请求重试也不会恢复
        if ("invalid_response".equals(code)) {
            return RuntimeErrorCode.MODEL_RESPONSE;
        }
        if (!ex.getStatusCode().is4xxClientError()) {
            return null;
        }
        // 平台错误码形如 system.40401 / model_nexus.40001，属于模型配置问题；其余为上游参数问题
        return status == 404 || code.contains(".") ? RuntimeErrorCode.MODEL_CONFIG : RuntimeErrorCode.MODEL_PARAM;
    }

    /**
     * 上游错误原文；没有时用 HTTP 状态描述。
     *
     * @param ex 模型服务的错误响应
     * @return 错误原文
     */
    private static String upstreamMessage(RestClientResponseException ex) {
        Object message = upstreamError(ex).get("message");
        return Objects.nonNull(message) ? message.toString() : ex.getStatusText();
    }

    /**
     * 解析 OpenAI 风格错误体的 {@code error} 对象；格式不符返回空 Map。
     *
     * @param ex 模型服务的错误响应
     * @return error 对象
     */
    private static Map<?, ?> upstreamError(RestClientResponseException ex) {
        try {
            return JSON.str2map(ex.getResponseBodyAsString()).get("error") instanceof Map<?, ?> error ? error : Map.of();
        } catch (RuntimeException _) {
            return Map.of();
        }
    }

    /**
     * 输出一次成功调用的摘要；异常时追加完整内容。
     *
     * @param use        调用用途
     * @param request    请求
     * @param response   响应
     * @param durationMs 耗时
     */
    private static void traceCall(ModelUse use, ChatCompletionRequest request, ChatCompletionResponse response,
                                  long durationMs) {
        if (!Trace.enabled(TraceStage.MODEL)) {
            return;
        }

        ChatCompletionChoice choice = Collections.isEmpty(response.getChoices()) ? null : response.getChoices().getFirst();
        ChatMessage message = Objects.isNull(choice) ? null : choice.getMessage();
        String content = Objects.isNull(message) ? null : message.getContent();
        List<ChatToolCall> calls = Objects.isNull(message) || Objects.isNull(message.getToolCalls())
            ? List.of() : message.getToolCalls();
        ChatCompletionUsage usage = response.getUsage();
        Abnormal abnormal = abnormalOf(response);

        TraceEvent event = Trace.at(TraceStage.MODEL, "call")
            .kv("use", use)
            .kv("model", response.getModel())
            .kv("durationMs", durationMs)
            .kv("promptTokens", Objects.isNull(usage) ? null : usage.getPromptTokens())
            .kv("completionTokens", Objects.isNull(usage) ? null : usage.getCompletionTokens())
            .kv("finishReason", Objects.isNull(choice) ? null : choice.getFinishReason())
            .kv("contentChars", Objects.isNull(content) ? 0 : content.length())
            .kv("toolCallCount", calls.size())
            .kv("toolNames", calls.stream().map(DefaultModelGateway::toolName).toList());
        if (Objects.isNull(abnormal)) {
            event.info();
        } else {
            event.kv("abnormal", abnormal).warn();
        }

        if (dumps(abnormal)) {
            dump(use, Part.REQUEST, request);
            dump(use, Part.RESPONSE, response);
        }
    }

    /**
     * 输出一次失败调用的摘要；按策略追加完整请求。
     *
     * @param use        调用用途
     * @param request    请求
     * @param ex         调用异常
     * @param durationMs 耗时
     */
    private static void traceFailure(ModelUse use, ChatCompletionRequest request, RuntimeException ex, long durationMs) {
        Trace.at(TraceStage.MODEL, "call_failed")
            .kv("use", use)
            .kv("durationMs", durationMs)
            .kv("error", ex.getClass().getSimpleName() + ": " + ex.getMessage())
            .kv("abnormal", Abnormal.ERROR)
            .warn();
        if (Trace.enabled(TraceStage.MODEL) && dumps(Abnormal.ERROR)) {
            dump(use, Part.REQUEST, request);
        }
    }

    /**
     * 判定响应是否异常。
     * <pre>{@code
     * abnormalOf(无 choices 的响应);           // EMPTY
     * abnormalOf(finish_reason=length 的响应); // LENGTH
     * abnormalOf(有正文的正常响应);            // null
     * }</pre>
     *
     * @param response 模型响应
     * @return 异常类型；正常为 {@code null}
     */
    static @Nullable Abnormal abnormalOf(ChatCompletionResponse response) {
        if (Collections.isEmpty(response.getChoices())) {
            return Abnormal.EMPTY;
        }

        ChatCompletionChoice choice = response.getChoices().getFirst();
        if ("length".equalsIgnoreCase(choice.getFinishReason())) {
            return Abnormal.LENGTH;
        }

        ChatMessage message = choice.getMessage();
        boolean hasContent = Objects.nonNull(message) && Strings.isNotBlank(message.getContent());
        boolean hasToolCalls = Objects.nonNull(message) && Collections.isNotEmpty(message.getToolCalls());
        return hasContent || hasToolCalls ? null : Abnormal.EMPTY;
    }

    /**
     * 按当前输出策略判断是否追加完整内容。
     *
     * @param abnormal 异常类型；正常为 {@code null}
     * @return 需要输出完整内容时返回 {@code true}
     */
    private static boolean dumps(@Nullable Abnormal abnormal) {
        return switch (Trace.modelPayload()) {
            case NONE -> false;
            case ABNORMAL -> Objects.nonNull(abnormal);
            case ALL -> true;
        };
    }

    /**
     * 输出一段完整内容：单行 JSON，按上限截断。
     *
     * @param use  调用用途
     * @param part 请求或响应
     * @param body 请求或响应对象
     */
    private static void dump(ModelUse use, Part part, Object body) {
        Trace.at(TraceStage.MODEL, "payload")
            .kv("use", use)
            .kv("part", part)
            .body(JSON.obj2str(body), Trace.modelMaxChars())
            .info();
    }

    /**
     * 工具调用的函数名。
     *
     * @param call 工具调用
     * @return 函数名；缺失为 {@code null}
     */
    private static @Nullable String toolName(ChatToolCall call) {
        return Objects.isNull(call.getFunction()) ? null : call.getFunction().getName();
    }

    /**
     * 自起点经过的毫秒数。
     *
     * @param startNanos 起点（{@link System#nanoTime()}）
     * @return 毫秒数
     */
    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 完整内容的组成部分。
     */
    private enum Part {

        /**
         * 请求
         */
        REQUEST,

        /**
         * 响应
         */
        RESPONSE
    }

    /**
     * 模型调用的异常类型。
     */
    enum Abnormal {

        /**
         * 没有 choices，或正文为空且没有工具调用
         */
        EMPTY,

        /**
         * 输出达到长度上限被截断
         */
        LENGTH,

        /**
         * 调用抛出异常
         */
        ERROR
    }
}
