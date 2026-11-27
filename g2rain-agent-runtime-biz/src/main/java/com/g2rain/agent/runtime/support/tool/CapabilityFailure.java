package com.g2rain.agent.runtime.support.tool;

/**
 * 能力工具调用失败：携带原因与下一步，由 {@link CapabilityToolCallback} 抛出。
 *
 * <p>不是 {@code ToolRejectedException}；{@code DefaultToolInvoker} 收成执行失败观测。</p>
 *
 * @author alpha
 * @since 2026/11/24
 */
public final class CapabilityFailure extends RuntimeException {

    /**
     * 失败原因。
     */
    private final String reason;

    /**
     * 模型下一步。
     */
    private final String next;

    /**
     * 是否因外部资源暂不可用而失败。
     */
    private final boolean unavailable;

    /**
     * 创建能力失败。
     *
     * @param reason 失败原因
     * @param next   下一步建议
     */
    public CapabilityFailure(String reason, String next) {
        this(reason, next, false);
    }

    /**
     * 创建能力失败，并标明是否因外部资源暂不可用。
     *
     * @param reason      失败原因
     * @param next        下一步建议
     * @param unavailable 是否因外部资源暂不可用
     */
    public CapabilityFailure(String reason, String next, boolean unavailable) {
        super(reason);
        this.reason = reason;
        this.next = next;
        this.unavailable = unavailable;
    }

    /**
     * 创建能力失败并保留原因异常。
     *
     * @param reason 失败原因
     * @param next   下一步建议
     * @param cause  原因异常
     */
    public CapabilityFailure(String reason, String next, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
        this.next = next;
        this.unavailable = false;
    }

    /**
     * @return 失败原因
     */
    public String reason() {
        return reason;
    }

    /**
     * @return 下一步建议
     */
    public String next() {
        return next;
    }

    /**
     * @return 是否因外部资源暂不可用而失败
     */
    public boolean unavailable() {
        return unavailable;
    }
}
