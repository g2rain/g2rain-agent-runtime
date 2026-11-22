package com.g2rain.agent.runtime.spi;

import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.exception.ErrorCode;

/**
 * 动作语义来源暂时不可用（依赖不可达、超时等，稍后重试可恢复）。
 *
 * <p>{@link SpecSource} 实现在依赖暂时不可用时抛本异常；其他异常一律视为该技能配置错误。</p>
 *
 * @author alpha
 * @since 2026/11/22
 */
public class SpecUnavailableException extends BusinessException {

    /**
     * 使用错误码与索引参数构造。
     *
     * @param errorCode 错误码
     * @param args      索引参数
     */
    public SpecUnavailableException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    /**
     * 使用错误码、原因与索引参数构造。
     *
     * @param errorCode 错误码
     * @param cause     原因
     * @param args      索引参数
     */
    public SpecUnavailableException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode, cause, args);
    }
}
