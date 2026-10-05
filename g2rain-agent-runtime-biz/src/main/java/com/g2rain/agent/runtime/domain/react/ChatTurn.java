package com.g2rain.agent.runtime.domain.react;


import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * ReAct 历史中的一轮消息（{@link ChatRole}：USER / ASSISTANT / TOOL）。
 *
 * <p>这是投影给模型的循环历史，不是落库的交互历史。多 tool_call 时走 {@link #toolCalls}，
 * 单次调用仍可用 {@code toolCallId} / {@code arguments} / {@code toolName}。</p>
 *
 * @param role       角色
 * @param content    正文；可空
 * @param toolCallId 工具调用 id；单次 TOOL / 旧单 tool_call 用；多 tool_call 时为空
 * @param arguments  工具参数 JSON；单次 tool_call 用
 * @param toolName   工具名；单次 tool_call / TOOL 轮使用
 * @param toolCalls  ASSISTANT 一轮内的多个 tool_call；非空时优先于 toolCallId
 * @author alpha
 * @since 2026/10/3
 */
public record ChatTurn(ChatRole role, @Nullable String content, @Nullable String toolCallId, @Nullable String arguments,
                       @Nullable String toolName, @Nullable List<ToolCallPart> toolCalls) {

    /**
     * 纯文字轮，无 tool_call。
     *
     * @param role    角色
     * @param content 正文；可空
     */
    public ChatTurn(ChatRole role, @Nullable String content) {
        this(role, content, null, null, null, null);
    }

    /**
     * 工具观察轮：{@code toolCallId} 用 evidenceId，无 arguments。
     *
     * @param role       角色
     * @param content    观察正文；可空
     * @param toolCallId 工具调用 id
     */
    public ChatTurn(ChatRole role, @Nullable String content, @Nullable String toolCallId) {
        this(role, content, toolCallId, null, null, null);
    }

    /**
     * 带 tool_call 参数、无 toolName 的轮次。
     *
     * @param role       角色
     * @param content    正文；可空
     * @param toolCallId 工具调用 id
     * @param arguments  参数 JSON；可空
     */
    public ChatTurn(ChatRole role, @Nullable String content, @Nullable String toolCallId, @Nullable String arguments) {
        this(role, content, toolCallId, arguments, null, null);
    }

    /**
     * 单次 tool_call / TOOL 轮。
     *
     * @param role       角色
     * @param content    正文；可空
     * @param toolCallId 工具调用 id
     * @param arguments  参数 JSON；可空
     * @param toolName   工具名；可空
     */
    public ChatTurn(ChatRole role, @Nullable String content, @Nullable String toolCallId, @Nullable String arguments,
                    @Nullable String toolName) {
        this(role, content, toolCallId, arguments, toolName, null);
    }

    /**
     * 同轮文字 + 多个 tool_call 的 ASSISTANT。
     *
     * @param content   助手正文；可空
     * @param toolCalls 本轮工具调用列表
     * @return ASSISTANT 历史轮
     */
    public static ChatTurn assistantWithCalls(@Nullable String content, List<ToolCallPart> toolCalls) {
        return new ChatTurn(ChatRole.ASSISTANT, content, null, null, null, toolCalls);
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
