package com.g2rain.agent.runtime.engine.model;

/**
 * 大模型调用用途：标明本次调用属于链路中的哪个步骤，用于调用轨迹。
 *
 * @author alpha
 * @since 2026/10/11
 */
public enum ModelUse {

    /**
     * 意图分类。
     */
    INTENT,

    /**
     * 规划前的技能候选筛选。
     */
    SKILL_CANDIDATE,

    /**
     * 规划出图。
     */
    PLAN,

    /**
     * 节点内循环。
     */
    REACT,

    /**
     * 收口时的目标判定与润色。
     */
    GOAL_GATE
}
