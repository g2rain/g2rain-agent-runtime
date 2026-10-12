package com.g2rain.agent.runtime.domain.react;


import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * ReAct 历史中的一轮消息（{@link ChatRole}：USER / ASSISTANT / TOOL）。
 *
 * <p>这是投影给模型的循环历史，不是落库的交互历史。ASSISTANT 的工具调用走 {@link #toolCalls}；
 * TOOL 轮用 {@code toolCallId} / {@code toolName} 回填调用结果。</p>
 *
 * @param role       角色
 * @param content    正文；可空
 * @param toolCallId TOOL 轮对应的调用 id；其他角色为空
 * @param toolName   TOOL 轮对应的工具名；其他角色为空
 * @param toolCalls  ASSISTANT 一轮内的工具调用；无调用时为空
 * @author alpha
 * @since 2026/10/3
 */
public record ChatTurn(ChatRole role, @Nullable String content, @Nullable String toolCallId,
                       @Nullable String toolName, @Nullable List<ToolCallPart> toolCalls) {

    /**
     * 纯文字轮，无工具调用。
     *
     * @param role    角色
     * @param content 正文；可空
     */
    public ChatTurn(ChatRole role, @Nullable String content) {
        this(role, content, null, null, null);
    }

    /**
     * 同轮文字 + 多个 tool_call 的 ASSISTANT。
     *
     * @param content   助手正文；可空
     * @param toolCalls 本轮工具调用列表
     * @return ASSISTANT 历史轮
     */
    public static ChatTurn assistantWithCalls(@Nullable String content, List<ToolCallPart> toolCalls) {
        return new ChatTurn(ChatRole.ASSISTANT, content, null, null, toolCalls);
    }

    /**
     * 工具调用结果回填轮。
     *
     * @param toolCallId 调用 id
     * @param toolName   工具名
     * @param content    调用结果；可空
     * @return TOOL 历史轮
     */
    public static ChatTurn tool(String toolCallId, String toolName, @Nullable String content) {
        return new ChatTurn(ChatRole.TOOL, content, toolCallId, toolName, null);
    }

    /**
     * ASSISTANT 上的一次 tool_call。
     *
     * @param id        调用 id（evidenceId）
     * @param name      工具名
     * @param arguments 参数摘要 JSON；可空
     */
    public record ToolCallPart(String id, String name, @Nullable String arguments) {
    }
}
