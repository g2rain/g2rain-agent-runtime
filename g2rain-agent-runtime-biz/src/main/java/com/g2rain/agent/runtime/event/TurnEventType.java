package com.g2rain.agent.runtime.event;

/**
 * 事件类型；同时写入 SSE 的 {@code event:} 行。
 *
 * @author alpha
 * @since 2026/8/27
 */
public enum TurnEventType {

    /**
     * 过程提示：可丢失，客户端用 {@code text} 覆盖状态行。
     */
    PROGRESS,

    /**
     * 本轮结果：必达，每轮恰好一帧且为最后一帧。
     */
    RESULT
}
