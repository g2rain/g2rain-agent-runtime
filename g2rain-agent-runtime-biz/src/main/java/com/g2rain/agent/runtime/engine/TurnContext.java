package com.g2rain.agent.runtime.engine;


import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.decision.GoalEvaluation;
import com.g2rain.agent.runtime.domain.decision.IntentResult;
import com.g2rain.agent.runtime.domain.entry.EntryResult;
import com.g2rain.agent.runtime.domain.execute.ExecutorResult;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 本轮流水线共享的可变工作台。
 *
 * <p>{@link #request} 与 {@link #turn} 在构造时固定；入口、意图、决策、技能、执行与收口
 * 产物由后续阶段写入。失败短路时 {@link #failed} 为 true，后续阶段应停止推进任务。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Getter
@Setter
public final class TurnContext {

    /**
     * 本轮入参。构造后只读引用，不随阶段改写。
     */
    private final TurnRequest request;

    /**
     * 本轮状态视图。来自 {@code RuntimeStateFacade.begin}，生命周期与本轮请求相同。
     */
    private final TurnScope turn;

    /**
     * 入口闸门结论（挂起恢复、拒答等）。闸门执行前为 {@code null}。
     */
    private @Nullable EntryResult entryResult;

    /**
     * 意图分类结果。决策前为 {@code null}。
     */
    private @Nullable IntentResult intentResult;

    /**
     * 本轮推进决策（续跑、新开、取消等）。决策前为 {@code null}。
     */
    private @Nullable TurnDecision decision;

    /**
     * 本轮绑定的 Agent 画像。未选人或仅回复时为 {@code null}。
     */
    private @Nullable AgentSelection agentSelection;

    /**
     * 本轮装载的技能列表。Hydrator 写入；装载前为 {@code null}。
     */
    private @Nullable List<Skill> skills;

    /**
     * 图执行结论。执行前为 {@code null}。
     */
    private @Nullable ExecutorResult executorResult;

    /**
     * 目标达成评估。收口前为 {@code null}。
     */
    private @Nullable GoalEvaluation goalEvaluation;

    /**
     * 回给调用方的本轮结果。收口或入口短路写入；此前为 {@code null}。
     */
    private @Nullable TurnResult result;

    /**
     * 是否已失败短路。入口拒答（非空提示）、选人失败或流水线异常时置 true。
     */
    private boolean failed;

    /**
     * 绑定本轮请求与已 begin 的状态视图。
     *
     * @param request 本轮入参
     * @param turn    状态门面 begin 得到的本轮视图
     */
    public TurnContext(TurnRequest request, TurnScope turn) {
        this.request = request;
        this.turn = turn;
    }

    /**
     * 用当前请求、技能与入口结论组装图调度帧。
     *
     * @return 图执行器使用的调度帧
     */
    public GraphFrame graphFrame() {
        return new GraphFrame(turn, skills, request.getUserInput(), request.getAction(), entryResult, agentSelection, request.getModel());
    }
}
