package com.g2rain.agent.runtime.spi;

/**
 * {@link ToolGuard} 放行时附带的警告：不拦下调用，但须让用户与模型知道。
 *
 * <p>{@code message} 面向用户，已按请求语言给出；需确认的写操作显示在确认内容中，
 * 其余调用标在执行过程里，并一并告知模型。</p>
 *
 * @param code    实现方规则编码
 * @param message 警告说明
 * @author alpha
 * @since 2026/11/21
 */
public record Notice(String code, String message) {

}
