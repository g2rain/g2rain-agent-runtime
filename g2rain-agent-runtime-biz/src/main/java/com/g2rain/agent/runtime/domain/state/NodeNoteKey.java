package com.g2rain.agent.runtime.domain.state;


import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 节点私有便签键：{@link NodeScope#note} 平台侧只走本枚举；Hash 字段名即 {@link #wire()}。
 *
 * @author alpha
 * @since 2026/10/3
 */
public enum NodeNoteKey {

    /**
     * 等待前置。
     * <p>正在等待的前置任务图节点 id；委派未完成时写入。</p>
     */
    AWAITING("awaiting"),

    /**
     * 委派收口观察。
     * <p>值为 {@link com.g2rain.agent.runtime.domain.state.task.DelegateResult}；投影消费后清除。</p>
     */
    DELEGATE_RESULT("delegateResult"),

    /**
     * ReAct 进度。
     * <p>当前节点推理-行动进度快照，供恢复或提示续写。</p>
     */
    PROGRESS("progress"),

    /**
     * 产出实体候选。
     * <p>finish 时放下的 output 实体，persist 时并入会话记忆。</p>
     */
    ENTITY_OUTPUTS("entityOutputs"),

    /**
     * 待批写调用。
     * <p>值为 {@link com.g2rain.agent.runtime.domain.state.task.PendingAction}：写闸以 APPROVAL_REQUIRED
     * 拦下的那次调用（工具名 + 绑定后参数 + 摘要）；审批消费后清除。</p>
     */
    GATED_WRITE("gatedWrite");

    /**
     * Redis / Hash 字段名。
     */
    private final String wire;

    NodeNoteKey(String wire) {
        this.wire = wire;
    }

    /**
     * @return 便签键线上名
     */
    @JsonValue
    public String wire() {
        return wire;
    }
}
