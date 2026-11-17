package com.g2rain.agent.runtime.spi;

/**
 * {@link ToolGuard} 的拒绝结果。
 *
 * <p>{@code code} 是实现方的规则编码；{@code message} 与 {@code nextStep} 进入观测文案
 * 「原因。下一步：…」。</p>
 *
 * @param code     实现方规则编码
 * @param message  拒绝原因（给模型）
 * @param nextStep 模型下一步
 * @author alpha
 * @since 2026/11/17
 */
public record Denial(String code, String message, String nextStep) {

}
