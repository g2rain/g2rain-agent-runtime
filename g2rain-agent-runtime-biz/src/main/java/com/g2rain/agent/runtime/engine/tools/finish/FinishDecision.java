package com.g2rain.agent.runtime.engine.tools.finish;


import org.jspecify.annotations.Nullable;

/**
 * 节点 finish 门槛结论。
 *
 * @param allowed 是否允许结束节点
 * @param reason  拒绝原因；允许时为 {@code null}
 * @author alpha
 * @since 2026/8/21
 */
public record FinishDecision(boolean allowed, @Nullable String reason) {

    /**
     * @return 允许 finish
     */
    public static FinishDecision allow() {
        return new FinishDecision(true, null);
    }

    /**
     * @param reason 拒绝原因
     * @return 拒绝 finish
     */
    public static FinishDecision reject(String reason) {
        return new FinishDecision(false, reason);
    }
}
