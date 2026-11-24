package com.g2rain.agent.runtime.spi;

import org.jspecify.annotations.Nullable;

/**
 * 能力工具一次调用的结果。
 *
 * <p>成功时 {@code content} 为给模型的结果文本；失败时 {@code content} 为原因、{@code next} 为下一步建议。</p>
 *
 * @param ok      是否成功
 * @param content 成功时的结果文本，或失败时的原因
 * @param next    失败时的下一步；成功时为 {@code null}
 * @author alpha
 * @since 2026/11/24
 */
public record CapabilityResult(boolean ok, String content, @Nullable String next) {

    /**
     * 成功结果。
     *
     * @param content 给模型的结果文本
     * @return 成功结果
     */
    public static CapabilityResult success(String content) {
        return new CapabilityResult(true, content, null);
    }

    /**
     * 失败结果。
     *
     * @param reason 失败原因
     * @param next   下一步建议
     * @return 失败结果
     */
    public static CapabilityResult failure(String reason, String next) {
        return new CapabilityResult(false, reason, next);
    }
}
