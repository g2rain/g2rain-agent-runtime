package com.g2rain.agent.runtime.engine.flow.entry;

import com.g2rain.agent.runtime.adapter.AgentProfiles;
import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.entry.EntryResult;
import com.g2rain.agent.runtime.domain.entry.RejectedReply;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.agent.studio.enums.ResourceStatus;
import com.g2rain.agent.studio.vo.AgentCatalogItemVo;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 默认本轮入口闸门：按活动任务与断点决定受理、拒回、换 Agent 或强制续跑。
 *
 * <p>任务身份取自会话 {@code activeTaskId}。只有请求显式携带确认/拒绝/取消时才强制走断点协议；
 * 审批断点超过 {@code reconfirm-after} 时改走普通受理，让后续意图分类重新判断。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class DefaultTurnEntryGuard implements TurnEntryGuard {

    /**
     * 校验请求显式指定的 Agent 是否存在且启用。
     */
    private final AgentProfiles agentProfiles;

    /**
     * 读取审批重新确认时限 {@code state.reconfirm-after}；调用期间只读。
     */
    private final RuntimeProperties properties;

    /**
     * 校验本轮入口并返回分流结论。
     *
     * <p>显式 Agent 不合法立即拒回。无活动任务或任务未挂起则普通受理。
     * 挂起且 Agent 与任务绑定不一致则换人；审批过期则退回受理；其余按断点白名单解析恢复动作。</p>
     *
     * @param context 本轮上下文（至少已带请求与会话视图）
     * @return 入口结论（accept / reject / switchAgent / continueSuspended）
     */
    @Override
    public EntryResult guard(TurnContext context) {
        TurnRequest request = context.getRequest();
        TurnScope turn = context.getTurn();
        String conversationId = turn.conversation().conversationId();

        Long agentId = request.getAgentId();
        EntryResult agentReject = validateExplicitAgent(conversationId, agentId);
        if (Objects.nonNull(agentReject)) {
            return agentReject;
        }

        String taskId = turn.conversation().activeTaskId();
        ResumeAction requested = request.getAction();
        // 带了确认/拒绝/取消，但现场没有可恢复断点：直接拒回，避免空跑协议
        if (isCheckpointAction(requested) && !hasLiveCheckpoint(turn, taskId)) {
            return EntryResult.reject(new RejectedReply(conversationId, Strings.blankToNull(taskId),
                RuntimeMessage.NOTHING_PENDING.text()));
        }

        if (Strings.isBlank(taskId) || turn.task().isEmpty()) {
            return EntryResult.accept(agentId);
        }

        TaskMetadata metadata = turn.task().orElseThrow();
        if (!isSuspended(metadata, turn)) {
            return EntryResult.accept(agentId);
        }

        TaskCheckpoint checkpoint = turn.checkpoint().orElse(null);
        if (Objects.isNull(checkpoint)) {
            return EntryResult.reject(new RejectedReply(conversationId, taskId, RuntimeMessage.TASK_GONE.text()));
        }

        String focusNodeId = metadata.getCurrentNodeId();
        Long boundAgentId = metadata.getAgentId();
        // 请求 Agent 与挂起任务绑定不一致：换人而不是在旧 Agent 上续跑
        if (Objects.nonNull(agentId) && Objects.nonNull(boundAgentId) && !Objects.equals(agentId, boundAgentId)) {
            return EntryResult.switchAgent(agentId, focusNodeId, checkpoint);
        }

        // 审批过期：不强制续跑，交给后续意图分类重新确认
        if (approvalExpired(metadata, checkpoint)) {
            return EntryResult.accept(agentId);
        }

        if (Objects.isNull(request.getAction())) {
            if (Strings.isNotBlank(request.getUserInput())) {
                return EntryResult.accept(agentId);
            }

            ResumeAction implied = resolveAction(null, checkpoint);
            if (Objects.nonNull(implied)) {
                return EntryResult.continueSuspended(agentId, focusNodeId, checkpoint, implied);
            }

            return EntryResult.reject(new RejectedReply(conversationId, taskId, RuntimeMessage.ACTION_MISSING.text()));
        }

        ResumeAction action = resolveAction(request.getAction(), checkpoint);
        if (Objects.isNull(action)) {
            return EntryResult.reject(new RejectedReply(conversationId, taskId, RuntimeMessage.ACTION_MISSING.text()));
        }

        String actionError = validateAction(action, checkpoint);
        if (Objects.nonNull(actionError)) {
            return EntryResult.reject(new RejectedReply(conversationId, taskId, actionError));
        }

        return EntryResult.continueSuspended(agentId, focusNodeId, checkpoint, action);
    }

    /**
     * 判断请求是否显式携带断点恢复动作。
     *
     * @param action 请求中的恢复动作
     * @return 为确认、拒绝或取消时返回 {@code true}
     */
    private static boolean isCheckpointAction(@Nullable ResumeAction action) {
        return action == ResumeAction.CONFIRM || action == ResumeAction.REJECT || action == ResumeAction.CANCEL;
    }

    /**
     * 判断任务是否同时具备可恢复状态和未消费断点。
     *
     * @param turn   当前会话视图
     * @param taskId 当前任务 id
     * @return 存在可用断点时返回 {@code true}
     */
    private static boolean hasLiveCheckpoint(TurnScope turn, @Nullable String taskId) {
        if (Strings.isBlank(taskId) || turn.task().isEmpty()) {
            return false;
        }

        return isSuspended(turn.task().orElseThrow(), turn) && turn.checkpoint().isPresent();
    }

    /**
     * 任务是否处于挂起：存在未消费断点，或元数据状态为 SUSPENDED。
     *
     * @param metadata 当前任务元数据
     * @param turn     当前会话视图
     * @return 可按挂起协议处理时返回 {@code true}
     */
    private static boolean isSuspended(TaskMetadata metadata, TurnScope turn) {
        return turn.checkpoint().isPresent() || TaskStatus.isSuspended(metadata.getStatus());
    }

    /**
     * 判断审批断点是否超过重新确认时限。
     *
     * <p>仅 APPROVAL 生效。超过时限后入口改为普通受理，由意图分类重新询问，而不是直接续跑待批工具。</p>
     *
     * @param metadata   当前任务元数据，读取 {@code suspendedAt}
     * @param checkpoint 当前断点
     * @return 超过时限时返回 {@code true}
     */
    private boolean approvalExpired(TaskMetadata metadata, TaskCheckpoint checkpoint) {
        if (checkpoint.getType() != CheckpointType.APPROVAL) {
            return false;
        }

        Long suspendedAt = metadata.getSuspendedAt();
        if (Objects.isNull(suspendedAt)) {
            return false;
        }

        long limit = properties.getState().getReconfirmAfter().toMillis();
        if (System.currentTimeMillis() - suspendedAt <= limit) {
            return false;
        }

        log.info("[EntryGuard] approval expired taskId={} errorCode={}", metadata.getTaskId(),
            RuntimeErrorCode.APPROVAL_EXPIRED);
        return true;
    }

    /**
     * 校验请求显式指定的 Agent 是否存在且处于启用状态。
     *
     * @param conversationId 会话 id
     * @param agentId        显式 Agent id
     * @return 校验失败时返回拒绝结果，否则返回 {@code null}
     */
    private @Nullable EntryResult validateExplicitAgent(String conversationId, @Nullable Long agentId) {
        if (Objects.isNull(agentId)) {
            return null;
        }

        AgentCatalogItemVo profile = agentProfiles.resolveProfile(agentId);
        if (Objects.isNull(profile)) {
            return EntryResult.reject(new RejectedReply(conversationId, null, RuntimeMessage.AGENT_MISSING.text()));
        }

        if (!ResourceStatus.ACTIVE.name().equals(profile.getStatus())) {
            return EntryResult.reject(new RejectedReply(conversationId, null, RuntimeMessage.AGENT_DISABLED.text()));
        }

        return null;
    }

    /**
     * 解析恢复动作；未显式提供时尝试使用断点允许的默认动作。
     *
     * @param action     请求显式动作
     * @param checkpoint 当前断点
     * @return 可执行动作；没有合法默认动作时返回 {@code null}
     */
    private static @Nullable ResumeAction resolveAction(@Nullable ResumeAction action, TaskCheckpoint checkpoint) {
        if (Objects.nonNull(action)) {
            return action;
        }

        ResumeAction candidate = checkpoint.getType().defaultResumeAction();
        if (Objects.isNull(candidate)) {
            return null;
        }

        if (isAllowed(candidate, checkpoint.getAllowedActions())) {
            return candidate;
        }

        return null;
    }

    /**
     * 校验恢复动作是否存在并被当前断点允许。
     *
     * @param action     待校验动作
     * @param checkpoint 当前断点
     * @return 错误文案；校验通过时返回 {@code null}
     */
    private static @Nullable String validateAction(@Nullable ResumeAction action, TaskCheckpoint checkpoint) {
        if (Objects.isNull(action)) {
            return RuntimeMessage.ACTION_MISSING.text();
        }

        if (!isAllowed(action, checkpoint.getAllowedActions())) {
            return RuntimeMessage.ACTION_DENIED.text();
        }

        return null;
    }

    /**
     * 判断动作是否在断点白名单内。
     *
     * @param action  待判断动作
     * @param allowed 断点允许的动作集合
     * @return 白名单包含动作时返回 {@code true}
     */
    private static boolean isAllowed(ResumeAction action, @Nullable List<ResumeAction> allowed) {
        return Objects.nonNull(allowed) && allowed.contains(action);
    }
}
