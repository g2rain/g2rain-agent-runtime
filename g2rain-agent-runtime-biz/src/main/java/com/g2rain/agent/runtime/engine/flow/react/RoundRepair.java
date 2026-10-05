package com.g2rain.agent.runtime.engine.flow.react;


import com.g2rain.model.nexus.schema.ChatMessage;

/**
 * RoundRepair（轮次纠偏）：在无进展时往历史里塞一条用户侧纠偏提示。
 *
 * <p>消息写入 ReAct 可变历史，下一轮模型能看到拒绝原因。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface RoundRepair {

    /**
     * 按原因生成纠偏消息。
     *
     * @param reason 纠偏原因
     * @return 写入历史的用户消息
     */
    ChatMessage message(RepairReason reason);
}
