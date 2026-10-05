package com.g2rain.agent.runtime.engine.flow.execute;


import com.g2rain.agent.runtime.engine.TurnContext;

/**
 * TaskGraphExecutor：按当前 Task 上的任务图执行本轮节点。
 * <p>
 * 典型在任务图准备完成后调用；执行结束后把结论写入上下文，供结果收口使用。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface TaskGraphExecutor {

    /**
     * 执行任务图：逐步推进节点，直到完成、挂起或失败。
     *
     * @param context 本轮上下文（须已有会话与 Task；失败时可能标记 failed）
     */
    void execute(TurnContext context);
}
