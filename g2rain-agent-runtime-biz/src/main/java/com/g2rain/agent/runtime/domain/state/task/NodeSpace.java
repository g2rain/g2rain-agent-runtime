package com.g2rain.agent.runtime.domain.state.task;


import com.g2rain.agent.runtime.domain.state.Sourced;

import java.util.List;
import java.util.Map;

/**
 * 节点空间：inputs / outputs / 私有便签 / 计数 / 无证据动作。
 *
 * <p>节点之间通过明确的输出引用交接数据，不直接改对方空间。
 * 热状态过大时由 {@link SpaceSpill} 占位，完整 JSON 走冷存储。</p>
 *
 * @param inputs  绑定输入（带来源证明）
 * @param outputs 节点产出（带来源证明）
 * @param state   私有便签（键见 {@link com.g2rain.agent.runtime.domain.state.NodeNoteKey}）
 * @param rounds  已累计 ReAct 轮次
 * @param tokens  累计 token
 * @param actions 无业务证据的动作（内置工具调用、被拒或推迟的调用、用户确认或拒绝），供跨轮重建节点历史
 * @author alpha
 * @since 2026/10/3
 */
public record NodeSpace(Map<String, Sourced<?>> inputs, Map<String, Sourced<?>> outputs, Map<String, Object> state,
                        int rounds, long tokens, List<NodeAction> actions) {

    public NodeSpace {
        inputs = Map.copyOf(inputs);
        outputs = Map.copyOf(outputs);
        state = Map.copyOf(state);
        actions = List.copyOf(actions);
    }
}
