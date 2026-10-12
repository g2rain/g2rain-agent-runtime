package com.g2rain.agent.runtime.engine;


import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.decision.TurnMode;
import org.jspecify.annotations.Nullable;

/**
 * 将处理模式展开为编排器可直接使用的决策快照。
 * <p>工厂方法为每种 {@link TurnMode} 生成一致的布尔组合；调用方不应分别修改这些开关来
 * 拼出新的语义。需要绑定 Agent 时使用 {@link #withAgent(AgentSelection)} 创建副本。</p>
 * <p>
 * 例：{@code TurnDecision.continueTask()} → 续跑已有计划；
 * {@code TurnDecision.startFollowup().withAgent(selection)} → 带前情新开并绑定 Agent。
 * </p>
 *
 * @param mode             本轮模式
 * @param shouldContinue   是否在已有 Task 上继续跑
 * @param createTask       是否新建 Task
 * @param useContinuity    是否加载任务前情
 * @param loadExistingPlan 是否沿用已有执行计划/图
 * @param createPlan       是否重新规划构图
 * @param abandonPrevious  是否放弃上一任务脉络（替换/换 Agent 等）
 * @param agent            本轮选定的 Agent 画像；未绑定时为 {@code null}
 * @author alpha
 * @since 2026/8/21
 */
public record TurnDecision(TurnMode mode, boolean shouldContinue, boolean createTask, boolean useContinuity,
                           boolean loadExistingPlan, boolean createPlan, boolean abandonPrevious,
                           @Nullable AgentSelection agent) {

    /**
     * 续跑已有任务：继续执行、加载已有计划，不新建 Task / 计划。
     *
     * @return CONTINUE 决策
     */
    public static TurnDecision continueTask() {
        // 继续现有任务只加载已有图，不创建新任务或新计划。
        return new TurnDecision(TurnMode.CONTINUE_TASK, true, false, false, true, false, false, null);
    }

    /**
     * 切换 Agent：放弃原脉络，新建 Task 与计划。
     *
     * @return SWITCH_AGENT 决策
     */
    public static TurnDecision switchAgent() {
        // SWITCH_AGENT：换 Agent 后新建 Task/计划，并放弃上一脉络
        return new TurnDecision(TurnMode.SWITCH_AGENT, false, true, false, false, true, true, null);
    }

    /**
     * 带前情开局：新建 Task 与计划，并使用任务前情。
     *
     * @return START_WITH_CONTEXT 决策
     */
    public static TurnDecision startFollowup() {
        // START_WITH_CONTEXT：新建 Task/计划，并加载任务前情
        return new TurnDecision(TurnMode.START_WITH_CONTEXT, false, true, true, false, true, false, null);
    }

    /**
     * 全新开局：新建 Task 与计划，不继承任务前情。
     *
     * @return START_FRESH 决策
     */
    public static TurnDecision startFresh() {
        // START_FRESH：全新 Task/计划，不用前情、不放弃标记（无上一脉络可弃）
        return new TurnDecision(TurnMode.START_FRESH, false, true, false, false, true, false, null);
    }

    /**
     * 替换：放弃上一脉络，新建 Task 与计划。
     *
     * @return REPLACE 决策
     */
    public static TurnDecision replace() {
        // REPLACE：放弃上一脉络后新建 Task/计划
        return new TurnDecision(TurnMode.REPLACE_TASK, false, true, false, false, true, true, null);
    }

    /**
     * 取消当前任务：放弃上一脉络，本轮不新建。
     *
     * @return CANCEL_TASK 决策
     */
    public static TurnDecision cancelTask() {
        // CANCEL_TASK：放弃上一脉络，本轮不新建、不构图
        return new TurnDecision(TurnMode.CANCEL_TASK, false, false, false, false, false, true, null);
    }

    /**
     * 空决策：本轮不推进任务。
     *
     * @return EMPTY 决策
     */
    public static TurnDecision empty() {
        // 仅回复不触碰任务生命周期。
        return new TurnDecision(TurnMode.REPLY_ONLY, false, false, false, false, false, false, null);
    }

    /**
     * 待用户确认：存在待确认写操作而用户只输入了文字时使用；不推进、不新建、不构图，只请用户确认。
     *
     * @return UNCERTAIN 决策
     */
    public static TurnDecision uncertain() {
        // UNCERTAIN：只请用户确认，不触碰任务生命周期
        return new TurnDecision(TurnMode.UNCERTAIN, false, false, false, false, false, false, null);
    }

    /**
     * 在保持其它标志不变的前提下，附上或替换 Agent 画像。
     *
     * @param next 新的 Agent 画像；可为 {@code null} 表示清空
     * @return 带新 agent 字段的决策副本
     */
    public TurnDecision withAgent(@Nullable AgentSelection next) {
        // 其它开关原样拷贝，只换 agent
        return new TurnDecision(mode, shouldContinue, createTask, useContinuity, loadExistingPlan, createPlan, abandonPrevious, next);
    }
}
