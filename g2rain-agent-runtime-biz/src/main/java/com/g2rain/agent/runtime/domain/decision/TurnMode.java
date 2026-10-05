package com.g2rain.agent.runtime.domain.decision;


/**
 * Turn（本轮）意图模式：当前请求如何处理任务。
 *
 * <p>入口决策把自然语言请求收敛为有限分支；每个分支再由编排决定是否继续现有任务、
 * 创建新任务或只生成回复。枚举值不携带任务数据，也不应使用 ordinal 参与持久化。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public enum TurnMode {

    /**
     * 全新开局（常见于第一次提出可执行目标）。
     * <p>
     * 新建 Task 与计划，不继承上一 Task 留下的任务前情。
     * 例：用户提出与之前无关的全新目标。
     * </p>
     */
    START_FRESH,

    /**
     * 继续当前 Task（执行过程中的推进 / 挂起恢复）。
     * <p>
     * 在已有 Task / 计划上继续执行，包括挂起后的恢复。
     * 例：用户确认审批后，接着跑焦点节点。
     * </p>
     */
    CONTINUE_TASK,

    /**
     * 带任务前情开局（上一 Task 结束后，相关新目标再开）。
     * <p>
     * 新建 Task 与计划，但继承上一 Task 留下的任务前情。
     * 不是挂起场景里的追问回复，而是新 Task 复用可过期衔接包。
     * 例：上一单查完订单后，用户接着问退款进度。
     * </p>
     */
    START_WITH_CONTEXT,

    /**
     * 替换当前 Task（执行中途改目标，同 Agent）。
     * <p>
     * 放弃当前进行中的 Task 脉络，同 Agent 按新目标重新开 Task / 计划。
     * 例：用户明确改口，不再做当前这件事。
     * </p>
     */
    REPLACE_TASK,

    /**
     * 切换 Agent（执行中途换人后重开）。
     * <p>
     * 放弃当前 Task 脉络，换 Agent 后重新开 Task / 计划。
     * 与 {@link #REPLACE_TASK} 同属「弃旧开新」，差别在是否换人。
     * </p>
     */
    SWITCH_AGENT,

    /**
     * 取消当前任务后本轮不再开新（结构化 CANCEL 按钮之外，模型也可给出）。
     * <p>
     * 放弃当前 Task 脉络，不新建、不构图。
     * </p>
     */
    CANCEL_TASK,

    /**
     * 仅回复（任意处理步骤都可能出现：本轮不推进 Task）。
     * <p>
     * 不续跑、不新建、不构图，只生成回复。
     * 例：闲聊或无需执行技能的简短问答。
     * </p>
     */
    REPLY_ONLY,

    /**
     * 意图不确定。
     * <p>
     * 无法可靠判断请求意图；本轮不得推进任务或写操作，只能向用户澄清。
     * </p>
     */
    UNCERTAIN;

    /**
     * 判断本轮是否不推进任务图。
     *
     * <p>{@code UNCERTAIN} 和 {@code REPLY_ONLY} 都不会创建、恢复或执行任务；其它模式表示
     * 请求意图是面向任务处理的，后续仍需经过入口校验。</p>
     *
     * @return {@code UNCERTAIN} / {@code REPLY_ONLY} 为 true
     */
    public boolean skipsTask() {
        return this == UNCERTAIN || this == REPLY_ONLY;
    }
}
