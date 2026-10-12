package com.g2rain.agent.runtime.engine.state.repo;

import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;

import java.util.Map;
import java.util.Objects;

/**
 * 仓储共用的 JSON 编解码、列宽与异常收口。
 *
 * @author alpha
 * @since 2026/10/12
 */
final class RepoSupport {

    /**
     * 结构化字段的 JSON 编解码器。
     */
    static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 共享值 Map。
     */
    static final TypeReference<Map<String, Sourced<?>>> SHARED_MAP = new TypeReference<>() {

    };

    /**
     * traceId 列宽上限。
     */
    static final int TRACE_MAX = 64;

    /**
     * 仓储共用工具，禁止实例化。
     */
    private RepoSupport() {
    }

    /**
     * 对象转 JSON 文本。
     *
     * @param value 对象；可空
     * @return JSON；{@code null} 入参为 {@code null}
     */
    static @Nullable String toJson(@Nullable Object value) {
        return Objects.isNull(value) ? null : JSON.obj2str(value);
    }

    /**
     * 把持久化异常收成状态不可用。
     *
     * @param e 原始异常
     * @return 业务异常；已是 {@link BusinessException} 则原样返回
     */
    static BusinessException unavailable(Exception e) {
        if (e instanceof BusinessException business) {
            return business;
        }
        return new BusinessException(RuntimeErrorCode.STATE_UNAVAILABLE);
    }
}
