package com.g2rain.agent.runtime.engine.flow.react;


import com.g2rain.agent.runtime.domain.state.NodeScope;
import org.jspecify.annotations.Nullable;

/**
 * PhaseHint（执行提示）：根据节点产出和证据情况，给模型一句当前处理方向提示。
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface PhaseHint {

    /**
     * 计算本节点当前处理方向的提示文案。
     *
     * @param node 当前节点视图；可空
     * @return 提示文案；无节点则为 null
     */
    @Nullable
    String hint(@Nullable NodeScope node);
}
