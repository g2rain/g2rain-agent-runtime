package com.g2rain.agent.runtime.domain.tool;


import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 一次工具调用后的观测结果。
 *
 * <p>观测把执行成功与否、说明、业务载荷和错误码放在同一结构中，便于调用层统一写入证据
 * 并让后续决策区分“调用失败”和“调用成功但没有数据”。例如：
 * {@code Observation.success("查到订单", orderJson)} 或 {@code failure("超时", "TIMEOUT")}。</p>
 *
 * @param ok        是否调用成功
 * @param message   说明文案（成功摘要或失败原因）
 * @param data      成功时的业务载荷；失败时通常为 {@code null}
 * @param errorCode 失败错误码；成功时为 {@code null}
 * @author alpha
 * @since 2026/8/21
 */
public record Observation(boolean ok, String message, @Nullable JsonNode data, @Nullable String errorCode) {

    /**
     * 构造成功观测。
     *
     * @param message 成功说明
     * @param data    业务载荷；可空
     * @return 成功观测
     */
    public static Observation success(String message, @Nullable JsonNode data) {
        // 成功观测保留业务载荷，错误码固定为空，避免同一结果同时表达成功和失败。
        return new Observation(true, message, data, null);
    }

    /**
     * 构造失败观测。
     *
     * @param message   失败说明
     * @param errorCode 错误码
     * @return 失败观测
     */
    public static Observation failure(String message, String errorCode) {
        // 失败观测不携带业务载荷，只保留可用于分类处理的错误码。
        return new Observation(false, message, null, errorCode);
    }
}
