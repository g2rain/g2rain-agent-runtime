package com.g2rain.agent.runtime.engine.flow.react;


import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.support.ChatMessages;
import org.springframework.stereotype.Component;

/**
 * DefaultRoundRepair：默认轮次纠偏文案。
 * <p>
 * 按 {@link RepairReason} 返回固定用户消息，引导模型改用控流工具。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Component
public class DefaultRoundRepair implements RoundRepair {

    /**
     * 按原因返回纠偏用户消息。
     *
     * @param reason 纠偏原因
     * @return 用户消息
     */
    @Override
    public ChatMessage message(RepairReason reason) {
        // finish 被拒：提示补齐产出或改走追问/失败
        if (reason == RepairReason.FINISH_REJECTED) {
            return ChatMessages.user("finish 未通过。按上一轮观察补齐产出后再调用 builtin_node_finish，或 follow_up / node_fail。");
        }

        // 默认：未调工具，要求直接调控流工具，禁止伪工具文本
        return ChatMessages.user(
            "上一轮未发起有效工具调用。请直接调用工具：完成用 builtin_node_finish，追问用 builtin_node_follow_up，失败用 builtin_node_fail。禁止在正文写伪工具调用文本。"
        );
    }
}
