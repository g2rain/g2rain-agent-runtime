package com.g2rain.agent.runtime.engine.flow.decision;


import com.g2rain.agent.runtime.domain.entry.EntryResult;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;

/**
 * TurnDispatcher（本轮调度器）：决定推进模式并绑定执行 Agent，产出 {@link TurnDecision}。
 *
 * <p>先根据入口硬协议或意图分类确定 {@link com.g2rain.agent.runtime.domain.decision.TurnMode}，
 * 再交给 {@link AgentResolver} 选人并写回上下文。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface TurnDispatcher {

    /**
     * 完成本轮决策（模式 + Agent）。
     *
     * @param ctx   本轮上下文（含会话、任务、请求）
     * @param entry 入口闸门结论
     * @return 本轮决策；选人失败时上下文标 failed
     */
    TurnDecision decide(TurnContext ctx, EntryResult entry);
}
