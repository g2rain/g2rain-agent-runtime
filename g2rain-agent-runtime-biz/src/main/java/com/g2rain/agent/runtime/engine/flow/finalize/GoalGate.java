package com.g2rain.agent.runtime.engine.flow.finalize;


import com.g2rain.agent.runtime.engine.TurnContext;
import org.jspecify.annotations.Nullable;

/**
 * GoalGate（目标门）：任务成功收口时核验用户目标是否达成，并合成给用户的最终答复。
 *
 * <p>任务未成功（失败、挂起、取消）时不核验，原样返回草稿。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface GoalGate {

    /**
     * 呈现本轮最终答复。
     *
     * @param ctx       本轮上下文
     * @param draft     执行器带回的文案（失败原因等）；可空
     * @param succeeded 任务是否终态成功
     * @return 给用户的答复；未成功且草稿为空时为 {@code null}
     */
    @Nullable
    String present(TurnContext ctx, @Nullable String draft, boolean succeeded);
}
