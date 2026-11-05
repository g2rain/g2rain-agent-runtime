package com.g2rain.agent.runtime.engine.flow.react;

import org.jspecify.annotations.Nullable;

/**
 * StallTracker（停滞跟踪）：统计连续无进展轮数，用于失败收口。
 *
 * <p>有进展时清零；无进展时加一，并记下最后一次无进展的工具名称与错误码，供停滞提示使用。
 * 本对象只活在单次节点循环内。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public class StallTracker {

    /**
     * 连续无进展轮数。
     */
    private int noProgressRounds;

    /**
     * 最后一次无进展的工具名称（契约 label，缺省为注册名）；与工具无关时为空。
     */
    private @Nullable String lastTool;

    /**
     * 最后一次无进展的错误码；与工具无关时为空。
     */
    private @Nullable String lastErrorCode;

    /**
     * 记一次有进展：清零连续无进展计数与最后一次记录。
     */
    public void recordProgress() {
        this.noProgressRounds = 0;
        this.lastTool = null;
        this.lastErrorCode = null;
    }

    /**
     * 记一次与工具无关的无进展（模型未调用工具、finish 被拒等）。
     */
    public void recordStall() {
        record(null, null);
    }

    /**
     * 记一次工具调用未成功的无进展。
     *
     * @param tool      工具名称
     * @param errorCode 错误码
     */
    public void recordStall(String tool, @Nullable String errorCode) {
        record(tool, errorCode);
    }

    /**
     * 是否已达到/超过允许的最大无进展轮数。
     *
     * @param maxNoProgressRounds 配置上限（至少按 1 计）
     * @return 超限则为 true
     */
    public boolean exceeded(int maxNoProgressRounds) {
        return noProgressRounds >= Math.max(1, maxNoProgressRounds);
    }

    /**
     * 最后一次无进展的工具名称。
     *
     * @return 工具名称；与工具无关时为空
     */
    public @Nullable String lastTool() {
        return lastTool;
    }

    /**
     * 最后一次无进展的错误码。
     *
     * @return 错误码；与工具无关时为空
     */
    public @Nullable String lastErrorCode() {
        return lastErrorCode;
    }

    private void record(@Nullable String tool, @Nullable String errorCode) {
        this.noProgressRounds++;
        this.lastTool = tool;
        this.lastErrorCode = errorCode;
    }
}
