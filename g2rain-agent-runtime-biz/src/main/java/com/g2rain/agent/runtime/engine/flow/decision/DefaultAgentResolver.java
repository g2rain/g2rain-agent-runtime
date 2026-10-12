package com.g2rain.agent.runtime.engine.flow.decision;

import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.decision.IntentResult;
import com.g2rain.agent.runtime.domain.decision.TurnMode;
import com.g2rain.agent.runtime.domain.entry.EntryResult;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.TurnStatus;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;
import com.g2rain.agent.runtime.engine.TurnResult;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceEvent;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * DefaultAgentResolver（默认 Agent 解析器）：按优先级为本轮决策绑定执行 Agent。
 *
 * <p>优先级：显式 agentId → 续跑锁任务 Agent → 意图选人 → 当前任务 Agent → 存活前情 Agent → 租户唯一 Agent → 失败。
 *
 * @author alpha
 * @since 2026/8/21
 */
@Component
@RequiredArgsConstructor
public final class DefaultAgentResolver implements AgentResolver {

    /**
     * 启用 Agent 目录：按 id 查画像与列表。
     */
    private final AgentCatalog agentCatalog;

    /**
     * 为本轮决策绑定执行 Agent。
     *
     * <ol>
     *   <li>显式 agentId（含续跑）→ 须 ACTIVE，否则失败
     *   <li>续跑且无显式 id → 锁任务绑定 Agent
     *   <li>选人模式且意图带 agentId → 校验 ACTIVE 后绑定
     *   <li>回退：当前任务 Agent → 存活前情 Agent → 租户唯一启用 Agent
     *   <li>仍无来源 → 失败
     * </ol>
     *
     * @param ctx      本轮上下文（可读任务/意图/会话，写回选人结果）
     * @param entry    入口结论（含请求显式指定的 agentId）
     * @param decision 决策步骤产出的推进方式
     */
    @Override
    public void route(TurnContext ctx, EntryResult entry, TurnDecision decision) {
        if (decision.mode().skipsTask() || decision.mode() == TurnMode.CANCEL_TASK) {
            ctx.setDecision(decision);
            return;
        }

        // 显式 agentId 永远优先（含续跑）；未启用则本轮失败
        Long explicitAgentId = entry.explicitAgentId();
        if (Objects.nonNull(explicitAgentId)) {
            AgentSelection explicit = agentCatalog.findActiveById(explicitAgentId);
            if (Objects.isNull(explicit)) {
                failNoMatch(ctx, RuntimeMessage.AGENT_MISSING, TurnStatus.FAILED, explicitAgentId);
                return;
            }

            bind(ctx, decision, explicit, Source.EXPLICIT);
            return;
        }

        // 续跑且无显式 id：锁任务已绑定的 Agent，避免中途换人
        if (decision.shouldContinue()) {
            AgentSelection fromTask = resolveTaskAgent(ctx.getTurn().task().orElse(null));
            if (Objects.isNull(fromTask)) {
                failNoMatch(ctx, RuntimeMessage.AGENT_MISSING, TurnStatus.FAILED, null);
                return;
            }

            bind(ctx, decision, fromTask, Source.CONTINUED_TASK);
            return;
        }

        // 选人模式下采用意图选中的 agentId；须仍为启用画像
        IntentResult intent = ctx.getIntentResult();
        Long picked = decision.mode().selectsAgent() && Objects.nonNull(intent) ? intent.getAgentId() : null;
        if (Objects.nonNull(picked)) {
            AgentSelection byId = agentCatalog.findActiveById(picked);
            if (Objects.isNull(byId)) {
                failNoMatch(ctx, RuntimeMessage.AGENT_MISSING, TurnStatus.FAILED, picked);
                return;
            }

            bind(ctx, decision, byId, Source.INTENT);
            return;
        }

        // 回退：当前任务 Agent → 存活任务前情 Agent → 租户唯一启用 Agent
        AgentSelection taskAgent = resolveTaskAgent(ctx.getTurn().task().orElse(null));
        if (Objects.nonNull(taskAgent)) {
            bind(ctx, decision, taskAgent, Source.CURRENT_TASK);
            return;
        }

        AgentSelection continuityAgent = resolveContinuityAgent(ctx.getTurn());
        if (Objects.nonNull(continuityAgent)) {
            bind(ctx, decision, continuityAgent, Source.CONTINUITY);
            return;
        }

        List<AgentSelection> active = agentCatalog.listActive();
        if (active.size() == 1) {
            AgentSelection sole = active.getFirst();
            if (Objects.nonNull(sole.getAgentId())) {
                AgentSelection selection = agentCatalog.findActiveById(sole.getAgentId());
                if (Objects.nonNull(selection)) {
                    bind(ctx, decision, selection, Source.SOLE);
                    return;
                }
            }
        }

        failNoMatch(ctx, RuntimeMessage.AGENT_NONE, TurnStatus.DONE, null);
    }

    /**
     * 从任务元数据解析绑定的 Agent。
     *
     * @param meta 当前任务元数据；可空
     * @return 选中画像；无法解析则为 {@code null}
     */
    private @Nullable AgentSelection resolveTaskAgent(@Nullable TaskMetadata meta) {
        if (Objects.isNull(meta)) {
            return null;
        }

        Long agentId = meta.getAgentId();
        if (Objects.nonNull(agentId)) {
            AgentSelection byId = agentCatalog.findActiveById(agentId);
            if (Objects.nonNull(byId)) {
                return byId;
            }

            return AgentSelection.of(agentId, null, null);
        }

        return null;
    }

    /**
     * 从任务前情记录解析关联 Agent。
     *
     * @param turn 当前会话视图
     * @return 选中画像；无前情 Agent 或格式非法则为 {@code null}
     */
    private @Nullable AgentSelection resolveContinuityAgent(TurnScope turn) {
        if (turn.records().isEmpty()) {
            return null;
        }

        String agentId = turn.records().getFirst().agentId();
        if (Strings.isBlank(agentId)) {
            return null;
        }

        try {
            return agentCatalog.findActiveById(Long.valueOf(agentId));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 写回上下文上的 Agent 选人，同步 decision 上的 agent 字段，并输出绑定轨迹。
     *
     * @param ctx      本轮上下文
     * @param decision 尚未绑定或待覆盖 Agent 的决策
     * @param agent    已解析的启用画像
     * @param source   人选来源
     */
    private static void bind(TurnContext ctx, TurnDecision decision, AgentSelection agent, Source source) {
        ctx.setAgentSelection(agent);
        ctx.setDecision(decision.withAgent(agent));
        Trace.at(TraceStage.DECISION, "agent_bound")
            .kv("agentId", agent.getAgentId())
            .kv("source", source)
            .info();
    }

    /**
     * 标记失败、写入回执，并把决策清空，阻止后续建任务或跑图；输出未绑定轨迹。
     *
     * @param ctx     本轮上下文
     * @param message 稳定文案枚举
     * @param status  回执状态
     * @param agentId 未能绑定的 Agent 主键；无具体人选为 {@code null}
     */
    private static void failNoMatch(TurnContext ctx, RuntimeMessage message, TurnStatus status, @Nullable Long agentId) {
        ctx.setFailed(true);
        TurnResult result = new TurnResult();
        result.setConversationId(ctx.getRequest().getConversationId());
        result.setTaskId(ctx.getTurn().task().map(TaskMetadata::getTaskId).orElse(null));
        result.setContent(message.text());
        result.setStatus(status);
        ctx.setResult(result);
        ctx.setDecision(TurnDecision.empty());
        TraceEvent event = Trace.at(TraceStage.DECISION, "agent_unbound").kv("reason", message).kv("agentId", agentId);
        // 指定或选中的员工不可用是失败；没有合适的员工只是本轮无人承接
        if (status == TurnStatus.FAILED) {
            event.error();
        } else {
            event.warn();
        }
    }

    /**
     * 人选来源，与解析优先级一一对应。
     */
    private enum Source {

        /**
         * 请求显式指定
         */
        EXPLICIT,

        /**
         * 续跑时沿用任务绑定的 Agent
         */
        CONTINUED_TASK,

        /**
         * 意图分类选中
         */
        INTENT,

        /**
         * 回退到当前任务的 Agent
         */
        CURRENT_TASK,

        /**
         * 回退到任务前情的 Agent
         */
        CONTINUITY,

        /**
         * 回退到租户唯一启用的 Agent
         */
        SOLE
    }
}
