package com.g2rain.agent.runtime.engine.flow.decision;

import com.g2rain.agent.runtime.adapter.AgentProfiles;
import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.decision.IntentResult;
import com.g2rain.agent.runtime.domain.decision.TurnMode;
import com.g2rain.agent.runtime.domain.entry.EntryResult;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.agent.studio.vo.AgentCatalogItemVo;
import com.g2rain.agent.studio.vo.SkillDigestVo;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 默认本轮调度器：先定推进模式，再经 {@link AgentResolver} 绑定执行 Agent。
 *
 * <p>入口强制续跑或换人时跳过模型分类；否则意图分类加后置校验。选人失败时上下文标 failed。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class DefaultTurnDispatcher implements TurnDispatcher {

    /**
     * JSON 编解码器：审计日志与候选列表序列化。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 意图分类器：非硬协议路径上判断推进模式与人选倾向。
     */
    private final IntentClassifier intentClassifier;

    /**
     * Agent 画像：启用目录与技能摘要。
     */
    private final AgentProfiles agentProfiles;

    /**
     * Agent 解析器：按优先级绑定执行 Agent。
     */
    private final AgentResolver agentResolver;

    /**
     * 规划候选技能条数上限：每 Agent 上限与跨 Agent 总上限。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 完成本轮决策：模式 + 选人。
     *
     * <ol>
     *   <li>定推进模式：硬协议（挂起续跑 / 换人）或意图分类加后置校验 → {@link TurnMode}
     *   <li>决策写入 {@code ctx.decision}
     *   <li>绑定 Agent：{@link AgentResolver#route}（显式 agentId 优先）
     *   <li>以 resolver 写回的决策为准返回（选人失败时上下文已标 failed）
     * </ol>
     *
     * @param ctx   本轮上下文（含会话、任务、请求）
     * @param entry 入口闸门结论
     * @return 已绑定 Agent 的决策；选人失败时上下文标 failed
     */
    @Override
    public TurnDecision decide(TurnContext ctx, EntryResult entry) {
        log.info("[Dispatcher] begin conversationId:{}, entry:{}",
            ctx.getRequest().getConversationId(), JSON.obj2str(entry)
        );

        // 先写模式，resolver 可能再用 withAgent 覆盖同一份决策
        TurnDecision mode = decideMode(ctx, entry);
        ctx.setDecision(mode);
        agentResolver.route(ctx, entry, mode);
        // 选人失败时 decision 已被写成 empty，不得退回未绑定的 mode
        TurnDecision bound = ctx.getDecision();
        TurnDecision result = Objects.nonNull(bound) ? bound : mode;
        log.info("[Dispatcher] done result:{}", JSON.obj2str(result));
        return result;
    }

    /**
     * 只定 {@link TurnMode}：硬协议优先，否则模型分类；存在待写动作时 UNCERTAIN 走风险门。
     *
     * @param ctx   本轮上下文
     * @param entry 入口闸门结论
     * @return 尚未绑定 Agent 的决策
     */
    private TurnDecision decideMode(TurnContext ctx, EntryResult entry) {
        if (entry.forceContinue()) {
            audit(ctx, "hard_protocol", writePending(ctx.getTurn()), TurnMode.CONTINUE_TASK);
            return TurnDecision.continueTask();
        }

        // 入口已判定 Agent 与挂起任务不一致：跳过模型，直接换人
        if (entry.agentMismatch()) {
            audit(ctx, "hard_protocol", writePending(ctx.getTurn()), TurnMode.SWITCH_AGENT);
            return TurnDecision.switchAgent();
        }

        TurnScope turn = ctx.getTurn();
        boolean write = writePending(turn);

        IntentResult intent = intentClassifier.classify(buildIntentRequest(ctx, write));
        ctx.setIntentResult(intent);
        if (Objects.isNull(intent) || Objects.isNull(intent.getMode())) {
            remember(ctx, TurnMode.UNCERTAIN, RuntimeMessage.ROUTE_UNCERTAIN.text());
            audit(ctx, write ? "write_gate" : "model", write, TurnMode.UNCERTAIN);
            return TurnDecision.uncertain();
        }

        // 待写动作未决时不允许模型用 UNCERTAIN 绕过确认/拒绝
        if (write && intent.getMode() == TurnMode.UNCERTAIN) {
            remember(ctx, TurnMode.UNCERTAIN, RuntimeMessage.ROUTE_UNCERTAIN.text());
            audit(ctx, "write_gate", true, TurnMode.UNCERTAIN);
            return TurnDecision.uncertain();
        }

        TurnDecision turnDecision = switch (intent.getMode()) {
            case CONTINUE_TASK -> TurnDecision.continueTask();
            case START_WITH_CONTEXT -> TurnDecision.startFollowup();
            case START_FRESH -> TurnDecision.startFresh();
            case REPLACE_TASK -> TurnDecision.replace();
            case SWITCH_AGENT -> TurnDecision.switchAgent();
            case CANCEL_TASK -> TurnDecision.cancelTask();
            case REPLY_ONLY -> TurnDecision.empty();
            case UNCERTAIN -> TurnDecision.uncertain();
        };
        audit(ctx, "model", write, turnDecision.mode());
        return postValidate(turnDecision, ctx, entry);
    }

    /**
     * 判断当前断点是否包含待处理的写动作。
     *
     * @param turn 当前会话视图
     * @return 存在审批断点或待执行工具时返回 {@code true}
     */
    private static boolean writePending(TurnScope turn) {
        TaskCheckpoint checkpoint = turn.checkpoint().orElse(null);
        if (Objects.isNull(checkpoint)) {
            return false;
        }

        if (checkpoint.getType() == CheckpointType.APPROVAL) {
            return true;
        }

        PendingAction pending = checkpoint.getPendingAction();
        return Objects.nonNull(pending) && Strings.isNotBlank(pending.toolName());
    }

    /**
     * 将规则裁剪出的模式写回上下文，供后续流水线使用。
     *
     * @param ctx       本轮上下文
     * @param mode      已确定的运行模式
     * @param userReply 可直接返回用户的文案
     */
    @SuppressWarnings("SameParameterValue")
    private static void remember(TurnContext ctx, TurnMode mode, @Nullable String userReply) {
        IntentResult result = new IntentResult();
        result.setMode(mode);
        result.setUserReply(userReply);
        result.complete(null);
        ctx.setIntentResult(result);
    }

    /**
     * 记录意图决策的可观测摘要，不记录用户原文或敏感参数。
     *
     * @param ctx          本轮上下文
     * @param stage        决策来源标识
     * @param writePending 是否存在待处理写动作
     * @param mode         最终运行模式
     */
    private static void audit(TurnContext ctx, String stage, boolean writePending, TurnMode mode) {
        log.info("[IntentAudit] traceId={} stage={} writePending={} mode={}", ctx.getTurn().traceId(), stage, writePending, mode);
    }

    /**
     * 拼意图分类 user JSON。
     *
     * <p>字段含义见 {@link IntentRequest}。任务记忆要等 Hydrator 之后才齐，此处不读证据。</p>
     *
     * @param ctx          本轮上下文
     * @param writePending 当前断点是否含待处理写动作
     * @return 分类输入
     */
    private IntentRequest buildIntentRequest(TurnContext ctx, boolean writePending) {
        TurnScope turn = ctx.getTurn();
        boolean hasActiveTask = turn.task()
            .map(TaskMetadata::getStatus)
            .filter(TaskStatus::isTransient)
            .isPresent();
        boolean hasContinuity = !turn.records().isEmpty();

        List<AgentCatalogItemVo> catalog = agentProfiles.listActiveCatalog();
        Long continuityAgentId = continuityAgentId(turn);
        List<AgentSelection> agentCandidates = AgentSelection.of(catalog);

        return new IntentRequest(
            hasActiveTask,
            hasContinuity,
            continuityAgentId,
            IntentRequest.ContinuitySnippet.from(hasContinuity ? turn.records().getFirst() : null),
            ctx.getRequest().getUserInput(),
            agentCandidates,
            IntentRequest.DialogueWindow.from(turn.dialogue(12)),
            null,
            resolveSkillCatalog(catalog),
            activeTaskOf(turn, hasActiveTask, writePending)
        );
    }

    /**
     * 组装活动任务摘要；无瞬时态任务时返回 {@code null}。
     *
     * @param turn          当前会话视图
     * @param hasActiveTask 是否存在可续跑的活动任务
     * @param writePending  是否存在待处理写动作
     * @return 活动任务摘要；无活动任务为 {@code null}
     */
    private static IntentRequest.@Nullable ActiveTask activeTaskOf(TurnScope turn, boolean hasActiveTask, boolean writePending) {
        if (!hasActiveTask) {
            return null;
        }
        TaskCheckpoint checkpoint = turn.checkpoint().orElse(null);
        String type = Objects.nonNull(checkpoint) ? checkpoint.getType().name() : null;
        List<String> expected = Objects.nonNull(checkpoint) && Collections.isNotEmpty(checkpoint.getExpectedFields())
            ? checkpoint.getExpectedFields() : List.of();
        String goal = turn.task().map(TaskMetadata::getGoalSummary).orElse(null);
        return new IntentRequest.ActiveTask(goal, type, expected, writePending);
    }

    /**
     * 从最近任务记录中解析可复用的 Agent 标识。
     *
     * @param turn 当前会话视图
     * @return 可解析的 Agent id；没有记录或格式非法时返回 {@code null}
     */
    private static @Nullable Long continuityAgentId(TurnScope turn) {
        if (turn.records().isEmpty()) {
            return null;
        }

        String raw = turn.records().getFirst().agentId();
        if (Strings.isBlank(raw)) {
            return null;
        }

        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 跨启用 Agent 收集技能短目录，受每 Agent 上限与总上限裁剪。
     *
     * @param catalog 启用中的 Agent 目录
     * @return 技能短目录；无启用项时为空列表
     */
    private List<IntentRequest.SkillCatalogItem> resolveSkillCatalog(List<AgentCatalogItemVo> catalog) {
        if (Collections.isEmpty(catalog)) {
            return List.of();
        }

        List<IntentRequest.SkillCatalogItem> out = new ArrayList<>();
        int perAgent = runtimeProperties.getPlan().getMaxSkillsPerAgent();
        int total = runtimeProperties.getPlan().getMaxSkillsTotal();
        for (AgentCatalogItemVo agent : catalog) {
            if (out.size() >= total) {
                break;
            }

            if (Objects.isNull(agent.getAgentId())) {
                continue;
            }

            List<SkillDigestVo> digests = agentProfiles.listSkillDigest(agent.getAgentId());
            if (Collections.isEmpty(digests)) {
                continue;
            }

            int added = 0;
            for (SkillDigestVo digest : digests) {
                if (added >= perAgent || out.size() >= total) {
                    break;
                }

                if (Strings.isBlank(digest.getSkillKey())) {
                    continue;
                }

                String shortDesc = Strings.isNotBlank(digest.getSemanticDesc())
                    ? digest.getSemanticDesc().trim()
                    : digest.getSkillName();
                out.add(new IntentRequest.SkillCatalogItem(
                    agent.getAgentId(),
                    digest.getSkillKey().trim(),
                    digest.getSkillName(),
                    shortDesc
                ));

                added++;
            }
        }

        return out;
    }

    /**
     * 对模型输出执行确定性后置校验，修正与当前 State 不相容的模式。
     *
     * @param decision 模型或规则产生的初始决策
     * @param ctx      本轮上下文
     * @param entry    入口步骤结果
     * @return 可安全交给后续流水线的决策
     */
    private static TurnDecision postValidate(TurnDecision decision, TurnContext ctx, EntryResult entry) {
        boolean continuable = ctx.getTurn().task()
            .map(TaskMetadata::getStatus)
            .filter(TaskStatus::isTransient)
            .isPresent();

        if (decision.mode() == TurnMode.CONTINUE_TASK && !continuable) {
            if (!ctx.getTurn().records().isEmpty()) {
                return TurnDecision.startFollowup();
            }

            return TurnDecision.startFresh();
        }

        // 没有可取消的活动任务：取消降级为仅回复
        if (decision.mode() == TurnMode.CANCEL_TASK && !continuable) {
            return TurnDecision.empty();
        }

        if (decision.mode() == TurnMode.START_WITH_CONTEXT && !decision.useContinuity()) {
            return TurnDecision.startFollowup();
        }

        if (Objects.nonNull(entry.explicitAgentId()) && decision.mode() == TurnMode.SWITCH_AGENT) {
            return TurnDecision.startFresh();
        }

        return decision;
    }
}
