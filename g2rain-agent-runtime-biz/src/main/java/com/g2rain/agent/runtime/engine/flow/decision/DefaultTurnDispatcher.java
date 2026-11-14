package com.g2rain.agent.runtime.engine.flow.decision;

import com.g2rain.agent.runtime.client.AgentProfiles;
import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.decision.IntentResult;
import com.g2rain.agent.runtime.domain.decision.TurnMode;
import com.g2rain.agent.runtime.domain.decision.TurnScene;
import com.g2rain.agent.runtime.domain.entry.EntryResult;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.agent.studio.vo.AgentCatalogItemVo;
import com.g2rain.agent.studio.vo.SkillDigestVo;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 默认本轮调度器：先定推进模式，再经 {@link AgentResolver} 绑定执行 Agent。
 *
 * <p>入口强制续跑或换人时跳过模型分类；否则按 {@link TurnScene} 调用意图分类，再由 {@link #settle} 校正为
 * 本轮生效的模式。选人失败时上下文标 failed。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Component
@RequiredArgsConstructor
public final class DefaultTurnDispatcher implements TurnDispatcher {

    /**
     * 意图分类读取的对话窗口条数。
     */
    private static final int DIALOGUE_LIMIT = 12;

    /**
     * 意图分类器：非硬协议路径上判断推进模式与人选。
     */
    private final IntentClassifier intentClassifier;

    /**
     * Agent 画像：启用目录、单条画像与技能摘要。
     */
    private final AgentProfiles agentProfiles;

    /**
     * Agent 解析器：按优先级绑定执行 Agent。
     */
    private final AgentResolver agentResolver;

    /**
     * 意图候选技能条数上限：每 Agent 上限与跨 Agent 总上限。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 完成本轮决策：模式 + 选人。
     *
     * <ol>
     *   <li>定推进模式：硬协议（挂起续跑 / 换人）或场景内意图分类加校正 → {@link TurnMode}
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
        // 先写模式，resolver 可能再用 withAgent 覆盖同一份决策
        TurnDecision mode = decideMode(ctx, entry);
        ctx.setDecision(mode);
        agentResolver.route(ctx, entry, mode);
        // 选人失败时 decision 已被写成 empty，不得退回未绑定的 mode
        TurnDecision bound = ctx.getDecision();
        return Objects.nonNull(bound) ? bound : mode;
    }

    /**
     * 只定 {@link TurnMode}：硬协议优先，否则按场景分类并校正。
     *
     * @param ctx   本轮上下文
     * @param entry 入口闸门结论
     * @return 尚未绑定 Agent 的决策
     */
    private TurnDecision decideMode(TurnContext ctx, EntryResult entry) {
        if (entry.forceContinue()) {
            traceProtocol(TurnMode.CONTINUE_TASK);
            return TurnDecision.continueTask();
        }

        // 入口已判定 Agent 与挂起任务不一致：跳过模型，直接换人
        if (entry.agentMismatch()) {
            traceProtocol(TurnMode.SWITCH_AGENT);
            return TurnDecision.switchAgent();
        }

        TurnScope turn = ctx.getTurn();
        boolean write = writePending(turn);
        TurnScene scene = TurnScene.of(hasActiveTask(turn), !turn.records().isEmpty(), write);
        Long lockedAgentId = entry.explicitAgentId();

        IntentResult intent = intentClassifier.classify(buildIntentRequest(ctx, scene, lockedAgentId, write));
        ctx.setIntentResult(intent);
        TurnMode mode = settle(intent, scene, Objects.nonNull(lockedAgentId), write);
        Trace.at(TraceStage.DECISION, "settled")
            .kv("source", DecisionSource.MODEL)
            .kv("scene", scene)
            .kv("proposed", Objects.isNull(intent) ? null : intent.getMode())
            .kv("settled", mode)
            .kv("locked", Objects.nonNull(lockedAgentId))
            .kv("writePending", write)
            .info();
        if (mode == TurnMode.UNCERTAIN) {
            // 确认追问只用固定文案，替换模型结论，避免输出模型的 userReply
            ctx.setIntentResult(confirmation(turn));
        }

        return decisionOf(mode);
    }

    /**
     * 将模型结论校正为本轮生效的模式。
     *
     * <ol>
     *   <li>结论缺失或不在场景可选范围内 → 场景兜底模式
     *   <li>存在待确认写操作且判为续跑 → {@code UNCERTAIN}（写操作只由显式确认推进）
     *   <li>需要选人但无人可选、且已给出说明 → {@code REPLY_ONLY}（只回复说明，不建任务）
     * </ol>
     *
     * <pre>{@code
     * settle(null, TurnScene.FRESH, false, false);         // START_FRESH
     * settle(null, TurnScene.WRITE_PENDING, false, true);  // UNCERTAIN
     * }</pre>
     *
     * @param intent       模型结论；可空
     * @param scene        本轮场景
     * @param agentLocked  是否已指定数字员工
     * @param writePending 是否存在待确认的写操作
     * @return 本轮生效的模式
     */
    static TurnMode settle(@Nullable IntentResult intent, TurnScene scene, boolean agentLocked, boolean writePending) {
        TurnMode mode = Optional.ofNullable(intent)
            .map(IntentResult::getMode)
            .filter(scene.modes(agentLocked)::contains)
            .orElse(scene.fallback());

        if (writePending && mode == TurnMode.CONTINUE_TASK) {
            return TurnMode.UNCERTAIN;
        }

        if (mode.selectsAgent() && Objects.nonNull(intent) && Objects.isNull(intent.getAgentId())
            && Strings.isNotBlank(intent.getUserReply())) {
            return TurnMode.REPLY_ONLY;
        }

        return mode;
    }

    /**
     * 待确认写操作的追问结论：回查断点询问写操作是否已完成，其余询问是否继续执行。
     *
     * @param turn 当前会话视图
     * @return 携带确认文案的 {@code UNCERTAIN} 结论
     */
    static IntentResult confirmation(TurnScope turn) {
        boolean verify = turn.checkpoint()
            .map(TaskCheckpoint::getType)
            .filter(type -> type == CheckpointType.VERIFY)
            .isPresent();
        IntentResult result = new IntentResult();
        result.setMode(TurnMode.UNCERTAIN);
        result.setUserReply((verify ? RuntimeMessage.VERIFY_ASK : RuntimeMessage.ROUTE_UNCERTAIN).text());
        return result.complete(null);
    }

    /**
     * 将生效模式映射为本轮决策。
     *
     * @param mode 生效模式
     * @return 尚未绑定 Agent 的决策
     */
    private static TurnDecision decisionOf(TurnMode mode) {
        return switch (mode) {
            case START_FRESH -> TurnDecision.startFresh();
            case START_WITH_CONTEXT -> TurnDecision.startFollowup();
            case CONTINUE_TASK -> TurnDecision.continueTask();
            case REPLACE_TASK -> TurnDecision.replace();
            case SWITCH_AGENT -> TurnDecision.switchAgent();
            case CANCEL_TASK -> TurnDecision.cancelTask();
            case REPLY_ONLY -> TurnDecision.empty();
            case UNCERTAIN -> TurnDecision.uncertain();
        };
    }

    /**
     * 判断是否存在运行或挂起中的任务。
     *
     * @param turn 当前会话视图
     * @return 任务处于瞬时态时返回 {@code true}
     */
    private static boolean hasActiveTask(TurnScope turn) {
        return turn.task().map(TaskMetadata::getStatus).filter(TaskStatus::isTransient).isPresent();
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
     * 输出入口硬协议直接给出的模式。
     *
     * @param settled 本轮生效的模式
     */
    private static void traceProtocol(TurnMode settled) {
        Trace.at(TraceStage.DECISION, "settled").kv("source", DecisionSource.PROTOCOL).kv("settled", settled).info();
    }

    /**
     * 组装意图分类输入。
     *
     * <p>字段含义见 {@link IntentRequest}。任务记忆要等 Hydrator 之后才齐，此处不读证据。</p>
     *
     * @param ctx           本轮上下文
     * @param scene         本轮场景
     * @param lockedAgentId 指定的数字员工；可空
     * @param writePending  当前断点是否含待处理写动作
     * @return 分类输入
     */
    private IntentRequest buildIntentRequest(TurnContext ctx, TurnScene scene, @Nullable Long lockedAgentId,
                                             boolean writePending) {
        TurnScope turn = ctx.getTurn();
        boolean locked = Objects.nonNull(lockedAgentId);
        List<AgentCatalogItemVo> catalog = candidatesOf(lockedAgentId);
        TaskRecord latest = turn.records().isEmpty() ? null : turn.records().getFirst();

        return new IntentRequest(
            scene,
            locked,
            ctx.getRequest().getUserInput(),
            continuityAgentId(turn),
            IntentRequest.ContinuitySnippet.from(latest),
            AgentSelection.of(catalog),
            IntentRequest.DialogueWindow.from(turn.dialogue(DIALOGUE_LIMIT)),
            null,
            resolveSkillCatalog(catalog, locked),
            activeTaskOf(turn, scene == TurnScene.ACTIVE_TASK || scene == TurnScene.WRITE_PENDING, writePending),
            ctx.getRequest().getModel()
        );
    }

    /**
     * 本轮候选：未指定时为全部启用员工；指定时只保留该员工，目录未命中再单条解析。
     *
     * @param lockedAgentId 指定的数字员工；可空
     * @return 候选目录
     */
    private List<AgentCatalogItemVo> candidatesOf(@Nullable Long lockedAgentId) {
        List<AgentCatalogItemVo> active = agentProfiles.listActiveCatalog();
        if (Objects.isNull(lockedAgentId)) {
            return active;
        }

        return active.stream()
            .filter(item -> Objects.equals(lockedAgentId, item.getAgentId()))
            .findFirst()
            .or(() -> Optional.ofNullable(agentProfiles.resolveProfile(lockedAgentId)))
            .map(List::of)
            .orElse(List.of());
    }

    /**
     * 组装活动任务摘要；无活动任务时返回 {@code null}。
     *
     * @param turn          当前会话视图
     * @param hasActiveTask 是否存在活动任务
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
     * 收集候选 Agent 的技能短目录，受每 Agent 上限与总上限裁剪。
     *
     * <p>指定数字员工时每 Agent 上限取总上限，保证能否处理的判断看得到该员工的全部技能。</p>
     *
     * @param catalog     候选 Agent 目录
     * @param fullCatalog 是否不按每 Agent 上限截断
     * @return 技能短目录；无候选时为空列表
     */
    private List<IntentRequest.SkillCatalogItem> resolveSkillCatalog(List<AgentCatalogItemVo> catalog, boolean fullCatalog) {
        if (Collections.isEmpty(catalog)) {
            return List.of();
        }

        List<IntentRequest.SkillCatalogItem> out = new ArrayList<>();
        int total = runtimeProperties.getPlan().getMaxSkillsTotal();
        int perAgent = fullCatalog ? total : runtimeProperties.getPlan().getMaxSkillsPerAgent();
        // 按 Agent 顺序取技能摘要，先受总上限约束，再受每 Agent 上限约束
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

                // 短描述优先用语义描述，缺失时退回技能名
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
     * 本轮模式的决定来源。
     */
    private enum DecisionSource {

        /**
         * 入口硬协议直接给出
         */
        PROTOCOL,

        /**
         * 意图模型给出并经校正
         */
        MODEL
    }
}
