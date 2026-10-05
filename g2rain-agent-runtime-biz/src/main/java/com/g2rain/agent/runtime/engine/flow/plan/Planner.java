package com.g2rain.agent.runtime.engine.flow.plan;


import com.g2rain.agent.runtime.engine.TurnContext;

/**
 * Planner：按本轮决策决定是否生成任务执行图。
 * <p>
 * 需要新建计划时选出技能步骤并写入 Task；失败时由实现类将上下文标为 failed。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface Planner {

    /**
     * 规划本轮任务图：沿用已有图则直接返回；否则生成步骤并写入当前 Task。
     *
     * @param ctx 本轮上下文（须已有决策；新建计划时还须有 Task 与能力集）
     */
    void plan(TurnContext ctx);
}
