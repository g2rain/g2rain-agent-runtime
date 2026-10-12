package com.g2rain.agent.runtime.engine.flow.react;


/**
 * StallTracker（停滞跟踪）：统计连续无进展轮数，用于失败收口。
 *
 * <p>有进展时清零；无进展时加一。本对象只活在单次节点循环内。</p>
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
     * 记一次有进展：清零连续无进展计数。
     */
    public void recordProgress() {
        this.noProgressRounds = 0;
    }

    /**
     * 记一次无进展：计数加一。
     */
    public void recordStall() {
        this.noProgressRounds++;
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
}
