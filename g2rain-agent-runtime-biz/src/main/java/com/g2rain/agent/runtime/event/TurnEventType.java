package com.g2rain.agent.runtime.event;

/**
 * 事件类型；同时写入 SSE 的 {@code event:} 行。
 *
 * @author alpha
 * @since 2026/8/27
 */
public enum TurnEventType {

    /**
     * 执行步骤：只带 {@code step}，客户端按 {@code stepId} 合并为时间线。
     */
    PROGRESS,

    /**
     * 会话标题：标题生成或变化时下发，{@code text} 为标题；当前仅在本轮新建会话时开局发一次。
     */
    TITLE,

    /**
     * 本轮结果：必达，每轮恰好一帧且为最后一帧。
     */
    RESULT
}
