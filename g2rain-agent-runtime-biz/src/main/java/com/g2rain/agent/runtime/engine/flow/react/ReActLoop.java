package com.g2rain.agent.runtime.engine.flow.react;


import com.g2rain.agent.runtime.domain.react.ReActLoopContext;
import com.g2rain.agent.runtime.domain.react.ReActLoopResult;

/**
 * ReActLoop（节点内循环）：在单个节点上反复推理与调工具，直到完成、挂起或失败。
 *
 * <p>实现类就地追加历史消息。退出由控流工具、停滞超限或轮次用尽触发。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface ReActLoop {

    /**
     * 跑节点内循环。
     *
     * @param context 循环上下文（帧、节点、历史、工具等）
     * @return 循环结果（节点态、文案、挂起信息等）
     */
    ReActLoopResult run(ReActLoopContext context);
}
