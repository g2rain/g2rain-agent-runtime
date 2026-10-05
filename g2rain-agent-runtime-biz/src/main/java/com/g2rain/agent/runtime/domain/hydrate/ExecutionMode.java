package com.g2rain.agent.runtime.domain.hydrate;

/**
 * 节点出图与执行模式。
 *
 * <p>写在技能契约上，决定该 Skill 是先规划构图还是直接进入单节点 ReAct 环。</p>
 *
 * @author alpha
 * @since 2026/9/10
 */
public enum ExecutionMode {

    /**
     * 规划执行。
     * <p>先产出 {@link com.g2rain.agent.runtime.domain.plan.PlanStep} 再构图调度。</p>
     */
    PLAN,

    /**
     * ReAct 循环。
     * <p>当前节点内推理-行动，直到完成、挂起或失败。</p>
     */
    REACT
}
