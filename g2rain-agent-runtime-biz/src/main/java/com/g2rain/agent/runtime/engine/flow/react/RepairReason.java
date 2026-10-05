package com.g2rain.agent.runtime.engine.flow.react;


/**
 * RepairReason（纠偏原因）：节点内循环判定无进展时的原因分类。
 *
 * <p>决定 {@link RoundRepair} 写入哪条固定提示。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public enum RepairReason {

    /**
     * 本轮模型未发起有效工具调用。
     */
    NO_TOOL_CALLS,

    /**
     * 完成被完成门槛策略拒绝。
     */
    FINISH_REJECTED
}
