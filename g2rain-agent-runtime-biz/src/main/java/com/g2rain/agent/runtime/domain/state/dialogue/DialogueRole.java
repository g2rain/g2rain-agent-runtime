package com.g2rain.agent.runtime.domain.state.dialogue;


/**
 * 交互历史发言角色。
 *
 * <p>只区分终端用户与 Agent 回复，不含系统或工具观察；工具观察走 ReAct {@code ChatRole.TOOL}。</p>
 *
 * @author alpha
 * @since 2026/9/9
 */
public enum DialogueRole {

    /**
     * 用户。
     * <p>终端用户实际说过的话。</p>
     */
    USER,

    /**
     * 智能体。
     * <p>Agent 回复给用户的话。</p>
     */
    AGENT
}
