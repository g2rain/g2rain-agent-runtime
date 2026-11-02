package com.g2rain.agent.runtime.engine.flow.lifecycle;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.decision.IntentResult;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.TurnStatus;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.task.EntityEntry;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;
import com.g2rain.agent.runtime.engine.TurnResult;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceScope;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 默认任务生命周期：按决策取消旧 Task 或 {@code turn.start} 开新任务。
 *
 * <p>续跑却找不到 Task 时把上下文标 failed 并写入失败回执。Prior 在开任务时按配置截取任务记录与实体。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class DefaultTaskLifecycle implements TaskLifecycle {

    /**
     * 截取 Prior 时使用的任务条数与实体个数上限。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 按决策准备本轮 Task：续跑校验 → 按需取消旧任务 → 按需新建。
     *
     * @param ctx 本轮上下文（须已有决策；开新任务前须已装会话）
     */
    @Override
    public void prepare(TurnContext ctx) {
        TurnDecision decision = Objects.requireNonNull(ctx.getDecision());
        if (decision.shouldContinue() && ctx.getTurn().task().isEmpty()) {
            Trace.at(TraceStage.TASK, "missing").kv("mode", decision.mode()).error();
            ctx.setFailed(true);
            TurnResult missing = new TurnResult();
            missing.setStatus(TurnStatus.FAILED);
            missing.setContent(RuntimeMessage.TASK_GONE.text());
            ctx.setResult(missing);
            return;
        }

        if (decision.abandonPrevious()) {
            ctx.getTurn().task().ifPresent(task -> {
                Trace.at(TraceStage.TASK, "abandoned").kv("taskId", task.getTaskId()).kv("mode", decision.mode()).info();
                // 先消费挂起再关任务，避免留下未清断点
                ctx.getTurn().resume(ResumeAction.CANCEL, "");
                ctx.getTurn().close(
                    TaskStatus.CANCELED, RuntimeMessage.REPLY_CANCELLED.text()
                );
            });
        }

        if (decision.createTask()) {
            openTask(ctx);
        }
    }

    /**
     * 写入新 Task 元数据并 {@code turn.start}；目标优先取意图 goalSummary，否则本轮用户原文。
     * 新任务 ID 写入轨迹作用域，之后本轮日志都带上该任务 ID。
     *
     * @param ctx 本轮上下文
     */
    private void openTask(TurnContext ctx) {
        TurnScope turn = ctx.getTurn();
        // 已选 Agent 优先，未选时取意图决策命中的 Agent
        AgentSelection agent = ctx.getAgentSelection();
        if (Objects.isNull(agent) && Objects.nonNull(ctx.getDecision())) {
            agent = ctx.getDecision().agent();
        }

        TaskMetadata metadata = new TaskMetadata();
        metadata.setTaskId(RuntimeUtils.uuid());
        metadata.setConversationId(turn.conversation().conversationId());
        metadata.setStatus(TaskStatus.RUNNING);
        metadata.setStartedAt(System.currentTimeMillis());
        metadata.setTraceId(turn.traceId());
        metadata.setSourceMessageId(sourceMessageId(turn));
        if (Objects.nonNull(agent)) {
            metadata.setAgentId(agent.getAgentId());
        }

        // 目标摘要优先取意图结论，原文另存于 request 供节点使用
        String goalSummary = Optional.ofNullable(ctx.getIntentResult())
            .map(IntentResult::getGoalSummary)
            .filter(Strings::isNotBlank)
            .orElse(ctx.getRequest().getUserInput());

        if (Strings.isNotBlank(goalSummary)) {
            metadata.setGoalSummary(goalSummary.trim());
        }
        metadata.setRequest(Strings.blankToNull(Strings.trim(ctx.getRequest().getUserInput())));

        // 开局：生成前情并切换为新任务，之后日志带新任务 ID
        String previousTaskId = turn.task().map(TaskMetadata::getTaskId).orElse(null);
        Prior prior = priorOf(turn);
        turn.start(metadata, prior);
        TraceScope.bindTask(metadata.getTaskId());
        Trace.at(TraceStage.TASK, "opened")
            .kv("taskId", metadata.getTaskId())
            .kv("mode", Objects.isNull(ctx.getDecision()) ? null : ctx.getDecision().mode())
            .kv("agentId", metadata.getAgentId())
            .kv("previousTaskId", previousTaskId)
            .info();
    }

    /**
     * 本轮用户消息的 id：开启任务的就是这一条；纯按钮动作没有用户消息时为空。
     *
     * @param turn 本轮视图
     * @return 消息 id；没有为 {@code null}
     */
    private static @Nullable String sourceMessageId(TurnScope turn) {
        return turn.allDialogue().stream()
            .filter(message -> message.getRole() == DialogueRole.USER && Objects.equals(message.getTraceId(), turn.traceId()))
            .reduce((_, last) -> last)
            .map(DialogueMessage::getMessageId)
            .orElse(null);
    }

    /**
     * 从会话任务记录与实体切出 Prior；条数超过配置时只保留尾部。
     *
     * @param turn 当前会话视图
     * @return 开任务写入的任务前情切片
     */
    private Prior priorOf(TurnScope turn) {
        List<TaskRecord> tasks = turn.records();
        int maxTasks = Math.max(0, runtimeProperties.getState().getPriorTasksMax());
        if (tasks.size() > maxTasks) {
            tasks = tasks.subList(tasks.size() - maxTasks, tasks.size());
        }

        Map<String, EntityEntry> entities = turn.entities();
        int maxEntities = Math.max(0, runtimeProperties.getState().getPriorEntitiesMax());
        if (entities.size() > maxEntities) {
            Map<String, EntityEntry> sliced = new LinkedHashMap<>();
            int skip = entities.size() - maxEntities;
            int i = 0;
            for (var entry : entities.entrySet()) {
                if (i++ >= skip) {
                    sliced.put(entry.getKey(), entry.getValue());
                }
            }
            entities = sliced;
        }

        return new Prior(tasks, entities, List.of(), "start");
    }
}
