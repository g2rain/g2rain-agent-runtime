package com.g2rain.agent.runtime.engine.flow.finalize;


import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnResult;

/**
 * Finalizer：本轮收口——评估目标、写回执、持久化。
 * <p>
 * 返回值即为本轮回执，保证非空（无业务回执时补失败兜底）。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface Finalizer {

    /**
     * 收口本轮：评估、呈现回执、落库，并返回最终回执。
     *
     * @param ctx 本轮上下文
     * @return 本轮 {@link com.g2rain.agent.runtime.engine.TurnResult}（非空）
     */
    TurnResult finalize(TurnContext ctx);
}
