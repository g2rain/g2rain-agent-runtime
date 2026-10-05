package com.g2rain.agent.runtime.engine.flow.lifecycle;


import com.g2rain.agent.runtime.engine.TurnContext;

/**
 * TaskLifecycle（任务生命周期）：按本轮决策把上下文里的 Task 准备好。
 * <p>
 * 入口侧已装好可能存在的旧 Task；本接口据此做续跑校验、废旧与新建。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface TaskLifecycle {

    /**
     * 按决策准备本轮 Task：续跑校验 → 按需废旧 → 按需新建。
     * <p>
     * 续跑却无 Task 时将上下文标为 failed 并写入失败回执；调用方应尽早收口。
     * </p>
     *
     * @param ctx 本轮上下文（须已有决策；会话在新建 Task 前须已装入）
     */
    void prepare(TurnContext ctx);
}
