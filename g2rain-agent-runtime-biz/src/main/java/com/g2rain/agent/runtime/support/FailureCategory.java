package com.g2rain.agent.runtime.support;

import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/**
 * 本轮失败分类：由异常链上的业务错误码决定面向用户的文案。
 *
 * <p>模型类失败（额度、密钥、参数、配置、限流、超时）重试或降级都无法恢复，属于致命失败：
 * 各处降级分支遇到时应直接上抛，由收口按分类生成文案。</p>
 */
public enum FailureCategory {

    QUOTA(RuntimeErrorCode.MODEL_QUOTA, RuntimeMessage.ERROR_MODEL_QUOTA),
    AUTH(RuntimeErrorCode.MODEL_AUTH, RuntimeMessage.ERROR_MODEL_AUTH),
    PARAM(RuntimeErrorCode.MODEL_PARAM, RuntimeMessage.ERROR_MODEL_PARAM),
    CONFIG(RuntimeErrorCode.MODEL_CONFIG, RuntimeMessage.ERROR_MODEL_CONFIG),
    RATE_LIMIT(RuntimeErrorCode.MODEL_RATE_LIMIT, RuntimeMessage.ERROR_MODEL_RATE_LIMIT),
    TIMEOUT(RuntimeErrorCode.MODEL_TIMEOUT, RuntimeMessage.ERROR_MODEL_TIMEOUT),
    INTERNAL(null, RuntimeMessage.ERROR_GENERIC);

    /**
     * 对应的业务错误码；{@link #INTERNAL} 为空。
     */
    private final @Nullable RuntimeErrorCode errorCode;

    /**
     * 面向用户的文案。
     */
    private final RuntimeMessage message;

    FailureCategory(@Nullable RuntimeErrorCode errorCode, RuntimeMessage message) {
        this.errorCode = errorCode;
        this.message = message;
    }

    /**
     * 按异常链上的业务错误码分类。
     *
     * @param ex 任意异常；可空
     * @return 失败分类；识别不了为 {@link #INTERNAL}
     */
    public static FailureCategory of(@Nullable Throwable ex) {
        String code = RuntimeUtils.businessCode(ex);
        return Arrays.stream(values())
            .filter(c -> Objects.nonNull(c.errorCode) && c.errorCode.code().equals(code))
            .findFirst()
            .orElse(INTERNAL);
    }

    /**
     * 致命失败直接上抛；供降级分支在 catch 首行调用。
     *
     * @param ex 捕获的异常
     */
    public static void throwIfFatal(RuntimeException ex) {
        if (of(ex) != INTERNAL) {
            throw ex;
        }
    }

    /**
     * @return 面向用户的文案
     */
    public String text() {
        return message.text();
    }
}
