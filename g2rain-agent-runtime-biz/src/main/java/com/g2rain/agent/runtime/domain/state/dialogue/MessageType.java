package com.g2rain.agent.runtime.domain.state.dialogue;

/**
 * 聊天消息类型，取值与表 {@code chat_message.message_type} 一致。
 *
 * @author alpha
 * @since 2026/9/10
 */
public enum MessageType {

    /**
     * 用户文本。
     * <p>终端用户实际说过的话，落库为 USER_TEXT。</p>
     */
    USER_TEXT,

    /**
     * Agent 回复。
     * <p>给用户看的最终或中间文字。</p>
     */
    AGENT_REPLY;

    /**
     * 由交互历史角色映射到落库消息类型。
     *
     * @param role 发言角色
     * @return 对应消息类型
     */
    public static MessageType from(DialogueRole role) {
        return switch (role) {
            case USER -> USER_TEXT;
            case AGENT -> AGENT_REPLY;
        };
    }
}
