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
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
     *   <li>意图无 agentId 且写了无法匹配回复 → 失败
     *   <li>意图已带 agentId → 校验 ACTIVE 后绑定
     *   <li>意图写了无法匹配的回复且无 agentId → 失败
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
                failNoMatch(ctx, RuntimeMessage.AGENT_MISSING, TurnStatus.FAILED);
                return;
            }

            bind(ctx, decision, explicit);
            return;
        }

        // 续跑且无显式 id：锁任务已绑定的 Agent，避免中途换人
        if (decision.shouldContinue()) {
            AgentSelection fromTask = resolveTaskAgent(ctx.getTurn().task().orElse(null));
            if (Objects.isNull(fromTask)) {
                failNoMatch(ctx, RuntimeMessage.AGENT_MISSING, TurnStatus.FAILED);
                return;
            }

            bind(ctx, decision, fromTask);
            return;
        }

        IntentResult intent = ctx.getIntentResult();
        boolean pickMode = Objects.nonNull(intent) && (intent.getMode() == TurnMode.START_FRESH
            || intent.getMode() == TurnMode.START_WITH_CONTEXT
            || intent.getMode() == TurnMode.SWITCH_AGENT);

        // 意图已写无法匹配的回复且未给 agentId：直接失败，不再回退
        if (pickMode && Objects.isNull(intent.getAgentId()) && Strings.isNotBlank(intent.getUserReply())) {
            failNoMatch(ctx, intent.getUserReply());
            return;
        }

        // 意图已回填 agentId：须仍为启用画像
        if (Objects.nonNull(intent) && Objects.nonNull(intent.getAgentId())) {
            AgentSelection byId = agentCatalog.findActiveById(intent.getAgentId());
            if (Objects.isNull(byId)) {
                failNoMatch(ctx, RuntimeMessage.AGENT_MISSING, TurnStatus.FAILED);
                return;
            }

            bind(ctx, decision, byId);
            return;
        }

        // 回退：当前任务 Agent → 存活任务前情 Agent → 租户唯一启用 Agent
        AgentSelection taskAgent = resolveTaskAgent(ctx.getTurn().task().orElse(null));
        if (Objects.nonNull(taskAgent)) {
            bind(ctx, decision, taskAgent);
            return;
        }

        AgentSelection continuityAgent = resolveContinuityAgent(ctx.getTurn());
        if (Objects.nonNull(continuityAgent)) {
            bind(ctx, decision, continuityAgent);
            return;
        }

        List<AgentSelection> active = agentCatalog.listActive();
        if (active.size() == 1) {
            AgentSelection sole = active.getFirst();
            if (Objects.nonNull(sole.getAgentId())) {
                AgentSelection selection = agentCatalog.findActiveById(sole.getAgentId());
                if (Objects.nonNull(selection)) {
                    bind(ctx, decision, selection);
                    return;
                }
            }
        }

        failNoMatch(ctx, RuntimeMessage.AGENT_NONE, TurnStatus.DONE);
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
     * 写回上下文上的 Agent 选人，并同步 decision 上的 agent 字段。
     *
     * @param ctx      本轮上下文
     * @param decision 尚未绑定或待覆盖 Agent 的决策
     * @param agent    已解析的启用画像
     */
    private static void bind(TurnContext ctx, TurnDecision decision, AgentSelection agent) {
        ctx.setAgentSelection(agent);
        ctx.setDecision(decision.withAgent(agent));
    }

    /**
     * 标记本轮失败并写入拒答 {@link TurnResult}，状态默认为 FAILED。
     *
     * @param ctx     本轮上下文
     * @param message 面向用户的失败说明
     */
    private static void failNoMatch(TurnContext ctx, String message) {
        failNoMatch(ctx, message, TurnStatus.FAILED);
    }

    /**
     * 按运行时文案标记本轮失败。
     *
     * @param ctx     本轮上下文
     * @param message 稳定文案枚举
     * @param status  回执状态
     */
    private static void failNoMatch(TurnContext ctx, RuntimeMessage message, TurnStatus status) {
        failNoMatch(ctx, message.text(), status);
    }

    /**
     * 标记失败、写入回执，并把决策清空，阻止后续建任务或跑图。
     *
     * @param ctx     本轮上下文
     * @param message 面向用户的失败说明
     * @param status  回执状态
     */
    private static void failNoMatch(TurnContext ctx, String message, TurnStatus status) {
        ctx.setFailed(true);
        TurnResult result = new TurnResult();
        result.setConversationId(ctx.getRequest().getConversationId());
        result.setTaskId(ctx.getTurn().task().map(TaskMetadata::getTaskId).orElse(null));
        result.setContent(message);
        result.setStatus(status);
        ctx.setResult(result);
        ctx.setDecision(TurnDecision.empty());
    }
}
