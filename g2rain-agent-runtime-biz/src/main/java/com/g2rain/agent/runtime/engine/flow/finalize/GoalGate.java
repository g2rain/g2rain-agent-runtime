package com.g2rain.agent.runtime.engine.flow.finalize;


import org.jspecify.annotations.Nullable;

/**
 * GoalGate（目标门）：把确定性草稿与证据收成给人看的最终答复。
 * <p>
 * 成功且开启时，可优先采纳长证据文案，必要时再调模型润色。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface GoalGate {

    /**
     * 呈现本轮最终文案。
     *
     * @param deterministic 确定性草稿（执行器/拣选结果）；可空
     * @param evidence      本轮证据包；可空
     * @param succeeded     任务是否终态成功
     * @return 给人看的答复文案
     */
    String present(@Nullable String deterministic, @Nullable GoalEvidencePack evidence, boolean succeeded);
}
