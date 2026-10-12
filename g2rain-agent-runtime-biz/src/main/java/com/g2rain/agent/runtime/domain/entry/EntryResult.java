package com.g2rain.agent.runtime.domain.entry;

import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * 入口阶段对当前请求的处理结论。
 *
 * <p>结果对象同时携带拒绝文案、任务恢复信息和 Agent 选择信息，使上层编排不必通过多个可变
 * 标志猜测入口分支。挂起任务的恢复依据由 {@link TaskCheckpoint} 提供。
 *
 * @param failedReply     拒绝时回给用户的文案；受理类场景为 {@code null}
 * @param forceContinue   是否强制在挂起任务上继续（续跑）
 * @param agentMismatch   是否判定需要切换 Agent（当前 Agent 与请求不一致）
 * @param explicitAgentId 显式指定或切换后的 Agent 主键；可空
 * @param currentNodeId   续跑或换 Agent 时关联的当前节点 id；可空
 * @param checkpoint      挂起检查点；续跑/换 Agent 时携带；可空
 * @param effectiveAction 续跑时生效的恢复动作（如 REPLY/CONFIRM）；可空
 * @author alpha
 * @since 2026/8/21
 */
public record EntryResult(@Nullable RejectedReply failedReply, boolean forceContinue, boolean agentMismatch,
                          @Nullable Long explicitAgentId, @Nullable String currentNodeId,
                          @Nullable TaskCheckpoint checkpoint, @Nullable ResumeAction effectiveAction) {

    /**
     * 拒绝入口：携带失败回包，不继续执行。
     *
     * @param failedReply 回给用户的失败文案
     * @return 拒绝结论
     */
    public static EntryResult reject(RejectedReply failedReply) {
        return new EntryResult(failedReply, false, false, null, null, null, null);
    }

    /**
     * 正常受理：可带显式 Agent id。
     *
     * @param explicitAgentId 显式 Agent 主键；可空表示沿用默认路由
     * @return 受理结论
     */
    public static EntryResult accept(@Nullable Long explicitAgentId) {
        return new EntryResult(null, false, false, explicitAgentId, null, null, null);
    }

    /**
     * 切换 Agent：标记 agentMismatch，并带上焦点节点与检查点（若有）。
     *
     * @param explicitAgentId 目标 Agent 主键
     * @param currentNodeId   关联当前节点；可空
     * @param checkpoint      挂起检查点；可空
     * @return 换 Agent 结论
     */
    public static EntryResult switchAgent(Long explicitAgentId, @Nullable String currentNodeId, @Nullable TaskCheckpoint checkpoint) {
        return new EntryResult(null, false, true, explicitAgentId, currentNodeId, checkpoint, null);
    }

    /**
     * 在挂起任务上继续：forceContinue=true，并带上恢复动作。
     *
     * @param explicitAgentId Agent 主键；可空
     * @param currentNodeId   当前节点 id；可空
     * @param checkpoint      挂起检查点
     * @param effectiveAction 本次生效的恢复动作
     * @return 续跑结论
     */
    public static EntryResult continueSuspended(@Nullable Long explicitAgentId, @Nullable String currentNodeId,
                                                TaskCheckpoint checkpoint, ResumeAction effectiveAction) {
        return new EntryResult(null, true, false, explicitAgentId, currentNodeId, checkpoint, effectiveAction);
    }

    /**
     * 本轮入口结论；拒绝优先，其次续跑、换人，其余为受理。
     *
     * @return 入口结论
     */
    public EntryOutcome outcome() {
        if (Objects.nonNull(failedReply)) {
            return EntryOutcome.REJECT;
        }

        if (forceContinue) {
            return EntryOutcome.CONTINUE;
        }

        if (agentMismatch) {
            return EntryOutcome.SWITCH;
        }

        return EntryOutcome.ACCEPT;
    }
}
