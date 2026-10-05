package com.g2rain.agent.runtime.domain.state.task;


import com.g2rain.agent.runtime.domain.state.Sourced;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * 节点空间：inputs / outputs / 私有便签 / 补参义务 / 计数。
 *
 * <p>节点之间通过明确的输出引用交接数据，不直接改对方空间。
 * 热状态过大时由 {@link SpaceSpill} 占位，完整 JSON 走冷存储。</p>
 *
 * @param inputs  绑定输入（带来源证明）
 * @param outputs 节点产出（带来源证明）
 * @param state   私有便签（键见 {@link com.g2rain.agent.runtime.domain.state.NodeNoteKey}）
 * @param repair  补参义务；每个节点同一时刻最多一条
 * @param rounds  已累计 ReAct 轮次
 * @param tokens  累计 token
 * @author alpha
 * @since 2026/10/3
 */
public record NodeSpace(Map<String, Sourced<?>> inputs, Map<String, Sourced<?>> outputs, Map<String, Object> state,
                        @Nullable RepairDirective repair, int rounds, long tokens) {

    public NodeSpace {
        inputs = Map.copyOf(inputs);
        outputs = Map.copyOf(outputs);
        state = Map.copyOf(state);
    }
}
