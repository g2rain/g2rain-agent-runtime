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
     * 产出实体候选。
     * <p>finish 时放下的 output 实体，persist 时并入会话记忆。</p>
     */
    ENTITY_OUTPUTS("entityOutputs"),

    /**
     * 待批写调用。
     * <p>值为 {@link com.g2rain.agent.runtime.domain.state.task.PendingAction}：写闸以 APPROVAL_REQUIRED
     * 拦下的那次调用（工具名 + 绑定后参数 + 摘要）；审批消费后清除。</p>
     */
    GATED_WRITE("gatedWrite"),

    /**
     * 前置条件拒绝。
     * <p>值为工具名：业务调用被前置条件以 {@code GUARD_DENIED} 拦下时写入；该工具之后放行或节点追问挂起时清除。
     * 存在期间由用户决定如何处理：不能失败，也不能执行其他写操作。</p>
     */
    GUARD_DENIAL("guardDenial"),

    /**
     * 前置条件补查。
     * <p>值为工具名：业务调用被前置条件以 {@code GUARD_LOOKUP} 拦下时写入；该工具之后放行或被拒绝时清除。
     * 存在期间须先补查并重新调用，不能追问、失败或完成。</p>
     */
    GUARD_LOOKUP("guardLookup");

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
