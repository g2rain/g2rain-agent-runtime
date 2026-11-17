package com.g2rain.agent.runtime.engine.model;

import com.g2rain.agent.runtime.i18n.RuntimeMessage;

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
    INTENT(RuntimeMessage.MODEL_INTENT),

    /**
     * 规划前的技能候选筛选。
     */
    SKILL_CANDIDATE(RuntimeMessage.MODEL_SKILL_CANDIDATE),

    /**
     * 规划出图。
     */
    PLAN(RuntimeMessage.MODEL_PLAN),

    /**
     * 节点内循环。
     */
    REACT(RuntimeMessage.MODEL_REACT),

    /**
     * 收口时的目标判定与润色。
     */
    GOAL_GATE(RuntimeMessage.MODEL_GOAL_GATE);

    /**
     * 进度步骤上展示的用途文案。
     */
    private final RuntimeMessage message;

    /**
     * @param message 用途文案
     */
    ModelUse(RuntimeMessage message) {
        this.message = message;
    }

    /**
     * @return 用途文案
     */
    public RuntimeMessage message() {
        return message;
    }
}
