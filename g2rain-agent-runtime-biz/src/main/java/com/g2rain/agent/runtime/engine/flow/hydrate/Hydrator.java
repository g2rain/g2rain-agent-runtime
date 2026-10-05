package com.g2rain.agent.runtime.engine.flow.hydrate;


import com.g2rain.agent.runtime.engine.TurnContext;

/**
 * Hydrator：给本轮装上 Agent 技能列表。
 *
 * <p>典型在路由与任务准备之后、规划之前调用。只写 {@code ctx.skills}，不改 Task 元数据。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface Hydrator {

    /**
     * 按已绑定 Agent 装载技能列表。
     *
     * @param ctx 本轮上下文；无 Agent 时保持 skills 为空
     */
    void hydrate(TurnContext ctx);
}
