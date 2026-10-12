package com.g2rain.agent.runtime.engine.flow.finalize;

import com.g2rain.agent.runtime.domain.decision.GoalEvaluation;
import com.g2rain.agent.runtime.domain.decision.IntentResult;
import com.g2rain.agent.runtime.domain.decision.TurnMode;
import com.g2rain.agent.runtime.domain.execute.ExecutorResult;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.TurnStatus;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.dialogue.MessageType;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;
import com.g2rain.agent.runtime.engine.TurnResult;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 默认本轮收口：评估任务结果、写入 Agent 回复；终态任务再 {@code turn.close}。
 *
 * <p>用户消息由入口处理。无业务回执时补失败兜底，保证返回值非空。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class DefaultFinalizer implements Finalizer {

    /**
     * 成功收口时核验目标并合成答复。
     */
    private final GoalGate goalGate;

    /**
     * 评估 → 呈现回执 → 终态任务关闭，并返回最终 {@link TurnResult}。
     *
     * @param ctx 本轮上下文
     * @return 非空回执
     */
    @Override
    public TurnResult finalize(TurnContext ctx) {
        GoalEvaluation evaluation = evaluate(ctx);
        ctx.setGoalEvaluation(evaluation);
        present(ctx, evaluation);
        TurnResult result = ensureResult(ctx);
        if (evaluation.terminal() && ctx.getTurn().task().isPresent()) {
            ctx.getTurn().close(evaluation.taskStatus(),
                Strings.defaultIfBlank(result.getContent(), defaultContent(evaluation.taskStatus())));
        }
        return result;
    }

    /**
     * 保证回执非空；缺失时补失败兜底并标 failed。
     *
     * @param ctx 本轮上下文
     * @return 已有回执，或新写入的失败回执
     */
    private static TurnResult ensureResult(TurnContext ctx) {
        TurnResult existing = ctx.getResult();
        if (Objects.nonNull(existing)) {
            return existing;
        }
        TurnResult result = new TurnResult();
        result.setConversationId(ctx.getTurn().conversation().conversationId());
        result.setTaskId(ctx.getTurn().task().map(TaskMetadata::getTaskId).orElse(null));
        result.setStatus(TurnStatus.FAILED);
        result.setContent(RuntimeMessage.ERROR_GENERIC.text());
        ctx.setFailed(true);
        ctx.setResult(result);
        return result;
    }

    /**
     * 按决策与执行回执评估任务态；取消或仅回复不跑 GoalGate 证据路径。
     *
     * @param ctx 本轮上下文
     * @return 任务终态、是否收口以及面向用户的摘要
     */
    private GoalEvaluation evaluate(TurnContext ctx) {
        TurnDecision decision = ctx.getDecision();
        if (Objects.nonNull(decision) && decision.mode() == TurnMode.CANCEL_TASK) {
            String reply = Optional.ofNullable(ctx.getIntentResult())
                .map(IntentResult::getUserReply)
                .filter(Strings::isNotBlank)
                .orElse(RuntimeMessage.REPLY_CANCELLED.text());
            return new GoalEvaluation(TaskStatus.CANCELED, true, reply);
        }
        if (Objects.nonNull(decision) && decision.mode().skipsTask()) {
            TaskStatus keep = ctx.getTurn().task().map(TaskMetadata::getStatus).orElse(TaskStatus.SUCCEEDED);
            String reply = Optional.ofNullable(ctx.getIntentResult())
                .map(IntentResult::getUserReply)
                .filter(Strings::isNotBlank)
                .orElse(decision.mode() == TurnMode.UNCERTAIN
                    ? RuntimeMessage.ROUTE_UNCERTAIN.text()
                    : RuntimeMessage.REPLY_ACK.text());
            return new GoalEvaluation(keep, false, reply);
        }
        ExecutorResult executed = ctx.getExecutorResult();
        TaskStatus status = resolveStatus(ctx.getTurn(), executed);
        boolean terminal = TaskStatus.isTerminal(status);
        boolean succeeded = terminal && status == TaskStatus.SUCCEEDED;
        String draft = Objects.isNull(executed) ? null : Strings.blankToNull(executed.message());
        String summary = goalGate.present(ctx, draft, succeeded);
        return new GoalEvaluation(status, terminal, summary);
    }

    /**
     * 写入 {@link TurnResult} 并追加一条 AGENT 交互历史；已失败则沿用现有回执正文。
     *
     * @param ctx        本轮上下文
     * @param evaluation 目标评估结论
     */
    private void present(TurnContext ctx, GoalEvaluation evaluation) {
        TurnResult existing = ctx.getResult();
        if (Objects.nonNull(existing) && ctx.isFailed()) {
            appendAgent(ctx, existing.getContent());
            return;
        }

        TurnScope turn = ctx.getTurn();
        ExecutorResult executed = ctx.getExecutorResult();
        TaskStatus status = evaluation.taskStatus();
        String content = evaluation.summary();
        if (Strings.isBlank(content)) {
            content = defaultContent(status);
        }

        List<ResumeAction> allowed = Objects.nonNull(executed) ? new ArrayList<>(executed.allowedActions()) : new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        if (status == TaskStatus.SUSPENDED) {
            TaskCheckpoint checkpoint = turn.checkpoint().orElse(null);
            if (Objects.nonNull(checkpoint)) {
                // 挂起时以检查点问题为准：审批问题已拼入待执行参数
                if (Strings.isNotBlank(checkpoint.getQuestion())) {
                    content = checkpoint.getQuestion();
                }
                if (Collections.isEmpty(allowed) && Collections.isNotEmpty(checkpoint.getAllowedActions())) {
                    allowed = new ArrayList<>(checkpoint.getAllowedActions());
                }
                if (Collections.isNotEmpty(checkpoint.getSuggestions())) {
                    suggestions = new ArrayList<>(checkpoint.getSuggestions());
                }
            }
        }

        TurnResult result = Objects.nonNull(existing) ? existing : new TurnResult();
        result.setConversationId(turn.conversation().conversationId());
        result.setTaskId(turn.task().map(TaskMetadata::getTaskId).orElse(null));
        result.setContent(content);
        result.setStatus(TurnStatus.of(status));
        result.setAllowedActions(allowed);
        result.setSuggestions(suggestions);
        if (ctx.isFailed() || status == TaskStatus.FAILED) {
            ctx.setFailed(true);
        }
        ctx.setResult(result);
        appendAgent(ctx, content);
    }

    /**
     * 把 Agent 答复写入交互历史；空白内容跳过。
     *
     * @param ctx          本轮上下文
     * @param agentContent 面向用户的正文；空白则不追加
     */
    private static void appendAgent(TurnContext ctx, @Nullable String agentContent) {
        if (Strings.isBlank(agentContent)) {
            return;
        }
        DialogueMessage agent = new DialogueMessage(DialogueRole.AGENT, agentContent.trim());
        agent.setMessageId(RuntimeUtils.uuid());
        agent.setTraceId(ctx.getTurn().traceId());
        agent.setType(MessageType.AGENT_REPLY);
        agent.setTaskId(ctx.getTurn().conversation().activeTaskId());
        ctx.getTurn().append(agent);
    }

    /**
     * 执行回执优先；无执行结果时沿用任务元数据状态，再没有则 FAILED。
     *
     * @param turn     本轮视图
     * @param executed 图执行回执；尚未跑图时为空
     * @return 用于收口映射的任务粗状态
     */
    private static TaskStatus resolveStatus(TurnScope turn, @Nullable ExecutorResult executed) {
        if (Objects.nonNull(executed)) {
            return executed.status();
        }
        return turn.task().map(TaskMetadata::getStatus).orElse(TaskStatus.FAILED);
    }

    /**
     * 评估未给出正文时的状态默认答复。
     *
     * @param status 任务粗状态
     * @return 非空默认文案
     */
    private static String defaultContent(TaskStatus status) {
        return switch (status) {
            case SUCCEEDED -> RuntimeMessage.REPLY_DONE.text();
            case SUSPENDED -> RuntimeMessage.REPLY_NEED_INPUT.text();
            case CANCELED -> RuntimeMessage.REPLY_CANCELLED.text();
            case FAILED, RUNNING -> RuntimeMessage.ERROR_GENERIC.text();
        };
    }
}
