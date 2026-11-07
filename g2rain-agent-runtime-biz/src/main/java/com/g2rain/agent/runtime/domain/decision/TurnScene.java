package com.g2rain.agent.runtime.domain.decision;

import java.util.List;

/**
 * 本轮意图判定所处的会话场景。
 *
 * <p>场景由运行态确定性得出，给出意图模型可选的推进模式（按规则匹配顺序排列）与模型无有效结论时的兜底模式。
 * 意图提示词中的场景规则与 {@link #modes(boolean)} 保持一致，调度器据此校正模型结论。</p>
 *
 * <pre>{@code
 * TurnScene scene = TurnScene.of(false, true, false);   // CONTINUITY
 * scene.modes(false);                             // [START_WITH_CONTEXT, START_FRESH, REPLY_ONLY]
 * scene.fallback();                               // START_FRESH
 * }</pre>
 *
 * @author alpha
 * @since 2026/10/10
 */
public enum TurnScene {

    /**
     * 新对话：没有活动任务，也没有可接续的任务前情。
     */
    FRESH(TurnMode.START_FRESH, List.of(TurnMode.START_FRESH, TurnMode.REPLY_ONLY)),

    /**
     * 可接续：没有活动任务，上一任务留有存活前情。
     */
    CONTINUITY(TurnMode.START_FRESH, List.of(TurnMode.START_WITH_CONTEXT, TurnMode.START_FRESH, TurnMode.REPLY_ONLY)),

    /**
     * 任务进行中：存在运行或挂起中的任务。
     */
    ACTIVE_TASK(TurnMode.CONTINUE_TASK, List.of(TurnMode.CANCEL_TASK, TurnMode.SWITCH_AGENT, TurnMode.REPLACE_TASK, TurnMode.CONTINUE_TASK, TurnMode.REPLY_ONLY)),

    /**
     * 等待确认：任务进行中，且断点上有待确认的写操作。打断类模式排在前面，避免用户的取消、改办别的事被当作续跑。
     */
    WRITE_PENDING(TurnMode.CONTINUE_TASK, List.of(TurnMode.CANCEL_TASK, TurnMode.REPLACE_TASK, TurnMode.SWITCH_AGENT, TurnMode.CONTINUE_TASK, TurnMode.REPLY_ONLY));

    /**
     * 模型无有效结论时的兜底模式。
     */
    private final TurnMode fallback;

    /**
     * 本场景可选模式，顺序即规则匹配顺序。
     */
    private final List<TurnMode> modes;

    TurnScene(TurnMode fallback, List<TurnMode> modes) {
        this.fallback = fallback;
        this.modes = modes;
    }

    /**
     * 由运行态得出场景；有活动任务时优先视为任务进行中，其中有待确认写操作时为等待确认。
     *
     * @param activeTask   是否存在运行或挂起中的任务
     * @param continuity   是否存在存活的任务前情
     * @param writePending 是否存在待确认的写操作
     * @return 本轮场景
     */
    public static TurnScene of(boolean activeTask, boolean continuity, boolean writePending) {
        if (activeTask) {
            return writePending ? WRITE_PENDING : ACTIVE_TASK;
        }

        return continuity ? CONTINUITY : FRESH;
    }

    /**
     * 本场景可选模式；指定数字员工时不允许换人。
     *
     * @param agentLocked 本轮是否已指定数字员工
     * @return 按规则匹配顺序排列的可选模式
     */
    public List<TurnMode> modes(boolean agentLocked) {
        return agentLocked ? modes.stream().filter(mode -> mode != TurnMode.SWITCH_AGENT).toList() : modes;
    }

    /**
     * 模型无有效结论时采用的模式。
     *
     * @return 兜底模式
     */
    public TurnMode fallback() {
        return fallback;
    }
}
