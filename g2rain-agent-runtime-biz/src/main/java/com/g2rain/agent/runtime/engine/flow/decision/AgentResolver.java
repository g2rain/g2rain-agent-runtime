package com.g2rain.agent.runtime.engine.flow.decision;


import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.entry.EntryResult;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;

/**
 * AgentResolver（Agent 解析器）：把本轮决策落到一个具体 Agent 上。
 * <p>
 * 输入入口结论与初步 {@link TurnDecision}，输出已绑定 {@link AgentSelection} 的决策；
 * 选不出有效 Agent 时由实现类把上下文标为失败。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface AgentResolver {

    /**
     * 为本轮决策绑定执行 Agent。
     *
     * @param ctx      本轮上下文（可读任务/意图/会话，写回选人结果）
     * @param entry    入口结论（含请求显式指定的 agentId）
     * @param decision 决策步骤产出的推进方式
     */
    void route(TurnContext ctx, EntryResult entry, TurnDecision decision);
}
