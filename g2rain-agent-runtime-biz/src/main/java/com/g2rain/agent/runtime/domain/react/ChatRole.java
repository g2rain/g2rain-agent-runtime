package com.g2rain.agent.runtime.domain.react;


/**
 * ReAct 历史消息角色。
 *
 * <p>对应投影给模型的 user / assistant / tool，不是会话落库的 {@code DialogueRole}。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public enum ChatRole {

    /**
     * 用户。
     * <p>由 {@code DialogueRole.USER} 投影而来。</p>
     */
    USER,

    /**
     * 助手。
     * <p>由 {@code DialogueRole.AGENT} 投影而来，对应模型侧 assistant。</p>
     */
    ASSISTANT,

    /**
     * 工具观察。
     * <p>由本节点 {@code EvidenceHead} 拼进历史，对应模型侧 tool。</p>
     */
    TOOL
}
