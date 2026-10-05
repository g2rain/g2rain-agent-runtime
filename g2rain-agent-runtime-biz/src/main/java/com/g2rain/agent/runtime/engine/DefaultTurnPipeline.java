package com.g2rain.agent.runtime.engine;


import com.g2rain.agent.runtime.domain.decision.TurnMode;
import com.g2rain.agent.runtime.domain.entry.EntryResult;
import com.g2rain.agent.runtime.domain.entry.RejectedReply;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.dialogue.MessageType;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.engine.flow.decision.TurnDispatcher;
import com.g2rain.agent.runtime.engine.flow.entry.TurnEntryGuard;
import com.g2rain.agent.runtime.engine.flow.execute.TaskGraphExecutor;
import com.g2rain.agent.runtime.engine.flow.finalize.Finalizer;
import com.g2rain.agent.runtime.engine.flow.hydrate.Hydrator;
import com.g2rain.agent.runtime.engine.flow.lifecycle.TaskLifecycle;
import com.g2rain.agent.runtime.engine.flow.plan.Planner;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 本轮流水线默认实现：入口闸门到收口。
 *
 * <p>阶段顺序固定为闸门 → 决策 → 任务生命周期 → 技能装载 → 规划 → 图执行 → 收口。
 * 闸门给出拒答时直接回结果，不再进入 {@link #doRun}。begin / flush / persist 与
 * 首条过程提示与结果帧由会话服务负责，本类只改 {@link TurnContext} 与 {@link TurnScope}。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultTurnPipeline implements TurnPipeline {

    /**
     * 入口闸门：挂起恢复合法性、空输入拒答等。
     */
    private final TurnEntryGuard entryGuard;

    /**
     * 本轮模式与 Agent 选择。
     */
    private final TurnDispatcher turnDispatcher;

    /**
     * 任务新建、续跑、替换或取消。
     */
    private final TaskLifecycle taskLifecycle;

    /**
     * 把 Agent 技能装入本轮上下文。
     */
    private final Hydrator hydrator;

    /**
     * 为本轮任务构图或加载已有图。
     */
    private final Planner planner;

    /**
     * 按图推进节点与 ReAct。
     */
    private final TaskGraphExecutor taskGraphExecutor;

    /**
     * 根据执行结果写最终文案与 TurnResult。
     */
    private final Finalizer finalizer;

    /**
     * 先过入口闸门；拒答则短路返回，否则跑完整阶段并收口。
     *
     * @param ctx 已 begin 的本轮上下文
     * @return 本轮结果
     */
    @Override
    public TurnResult run(TurnContext ctx) {
        EntryResult entry = entryGuard.guard(ctx);
        ctx.setEntryResult(entry);
        RejectedReply rejected = entry.failedReply();
        // 闸门拒答：不进 doRun，仍要写交互历史后立刻返回
        if (Objects.nonNull(rejected)) {
            // 无待处理是提示而非失败；其它拒答才置 failed，避免后续阶段误推进
            boolean notice = RuntimeMessage.NOTHING_PENDING.text().equals(rejected.content());
            TurnResult early = notice
                ? TurnResult.done(rejected.conversationId(), rejected.taskId(), rejected.content())
                : TurnResult.fail(rejected.conversationId(), rejected.taskId(), rejected.content());

            ctx.setResult(early);
            if (!notice) {
                ctx.setFailed(true);
            }

            TurnScope turn = ctx.getTurn();
            String content = early.getContent();
            if (Strings.isNotBlank(content)) {
                DialogueMessage agent = new DialogueMessage(
                    DialogueRole.AGENT, content
                );

                String taskId = turn.conversation().activeTaskId();
                agent.setMessageId(RuntimeUtils.uuid());
                agent.setTraceId(turn.traceId());
                agent.setType(MessageType.AGENT_REPLY);
                agent.setTaskId(taskId);
                turn.append(agent);
            }

            return early;
        }

        doRun(ctx, entry);
        // 无论 doRun 成功、短路还是失败，都走收口拼最终文案
        return finalizer.finalize(ctx);
    }

    /**
     * 决策到图执行。任一步失败或模式跳过任务时提前返回，由 {@link Finalizer} 收口。
     *
     * <p>未捕获异常会把任务与节点标 FAILED，并在尚未写入结果时补一条失败 {@link TurnResult}。</p>
     *
     * @param ctx   本轮上下文
     * @param entry 入口闸门已通过的结论
     */
    private void doRun(TurnContext ctx, EntryResult entry) {
        try {
            TurnDecision decision = turnDispatcher.decide(ctx, entry);
            ctx.setDecision(decision);
            // 决策失败：不建任务、不构图，交给收口
            if (ctx.isFailed()) {
                return;
            }

            // 仅回复 / 拿不准：不建任务、不构图
            if (decision.mode().skipsTask()) {
                return;
            }

            taskLifecycle.prepare(ctx);
            // 取消已在生命周期内落终态，不再装载技能
            if (decision.mode() == TurnMode.CANCEL_TASK) {
                return;
            }
            // 生命周期/装载/构图任一步失败都立刻交给收口，避免半成品图被执行
            if (ctx.isFailed()) {
                return;
            }

            hydrator.hydrate(ctx);
            if (ctx.isFailed()) {
                return;
            }

            planner.plan(ctx);
            if (ctx.isFailed()) {
                return;
            }

            taskGraphExecutor.execute(ctx);
        } catch (RuntimeException ex) {
            log.error("[Pipeline] error conversationId={}",
                ctx.getRequest().getConversationId(), ex
            );

            ctx.setFailed(true);
            TurnScope turn = ctx.getTurn();
            String msg = RuntimeMessage.ERROR_GENERIC.text();
            turn.fail(msg, RuntimeUtils.businessCode(ex));

            // 阶段内已写过结果则保留，避免覆盖更具体的失败文案
            if (Objects.nonNull(ctx.getResult())) {
                return;
            }

            String conversationId = turn.conversation().conversationId();
            String taskId = turn.task().map(TaskMetadata::getTaskId).orElse(null);
            ctx.setResult(TurnResult.fail(conversationId, taskId, msg));
        }
    }
}
