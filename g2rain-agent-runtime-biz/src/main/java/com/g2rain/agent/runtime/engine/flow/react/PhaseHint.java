package com.g2rain.agent.runtime.engine.flow.react;


import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import org.jspecify.annotations.Nullable;

/**
 * PhaseHint（执行提示）：给模型一句本节点完成义务的提示。
 *
 * <p>提示只陈述可判定的事实（完成目标是否已成功、必填产出是否写齐），不推断任务进度，下一步由模型自行决定。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface PhaseHint {

    /**
     * 计算本节点完成义务的提示文案。
     *
     * @param node     当前节点视图；可空
     * @param contract 当前技能契约；可空
     * @param target   节点完成目标（写工具注册名）；可空
     * @param claimed  完成目标由首次写调用认领（推测而来）
     * @return 提示文案；既无完成目标又无必填产出时为 null
     */
    @Nullable
    String hint(@Nullable NodeScope node, @Nullable SkillContract contract, @Nullable String target, boolean claimed);
}
