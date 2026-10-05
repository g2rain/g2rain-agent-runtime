package com.g2rain.agent.runtime.domain.hydrate;

/**
 * 工具成功判定运算符（{@code successOp}）。
 *
 * @author alpha
 * @since 2026/9/10
 */
public enum SuccessOp {

    /**
     * path 取值等于 {@code successValue}（宽松等值，200 == "200"）。
     */
    EQ,

    /**
     * path 取值不等于 {@code successValue}。
     */
    NE,

    /**
     * path 取值落在 {@code successValue} 数组中。
     */
    IN,

    /**
     * path 取值不在 {@code successValue} 数组中。
     */
    NOT_IN,

    /**
     * path 值为真（非 null / 非 false / 非 0 / 非空串 / 非空集合）。
     */
    TRUTHY,

    /**
     * path 存在（不论值，null 也算存在）。
     */
    EXISTS
}
