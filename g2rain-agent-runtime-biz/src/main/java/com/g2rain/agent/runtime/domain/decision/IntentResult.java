package com.g2rain.agent.runtime.domain.decision;


import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 意图判定阶段产生的可变结果。
 *
 * <p>对象先承接分类器或模型输出，再由 {@link #complete(List)} 做边界收口：规范化文本、
 * 强制模式与前情的一致性，并确认选择的 Agent 确实属于当前候选集合。调用方不应直接把未经
 * {@code complete} 处理的实例作为任务路由依据。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Getter
@Setter
@NoArgsConstructor
public final class IntentResult {

    /**
     * 当前请求的处理模式；为空表示分类结果尚未收口。
     */
    private @Nullable TurnMode mode;

    /**
     * 是否允许本轮使用已有任务前情。
     * <p>收口时只有 {@link TurnMode#START_WITH_CONTEXT} 会保留为 {@code true}，避免模式与
     * 上下文策略互相矛盾。</p>
     */
    private boolean useContinuity;

    /**
     * 对用户目标的短摘要，供后续规划和审计展示；可空。
     */
    private @Nullable String goalSummary;

    /**
     * 分类器选中的 Agent 主键；收口时若不在候选列表中会被清空。
     */
    private @Nullable Long agentId;

    /**
     * 不启动或不能启动任务时直接返回给用户的文案；可空。
     */
    private @Nullable String userReply;

    /**
     * 用于日志或审计的判定理由；不作为业务状态机的唯一依据。
     */
    private @Nullable String reason;

    /**
     * 规范化并校验分类结果，使它可以安全地交给入口路由。
     *
     * <p>方法会清除文本首尾空白；不需要 Agent 的模式会清除 {@code agentId}；不确定模式
     * 会补充默认追问文案；当候选列表可用时，还会拒绝不存在的 Agent id。方法原地修改并
     * 返回当前实例，便于在构造模型输出后立即收口。</p>
     *
     * @param candidates Agent 候选列表；可空/空则跳过 id 校验
     * @return this（便于链式调用）
     */
    public IntentResult complete(@Nullable List<AgentSelection> candidates) {
        // 只有「带前情再开」才允许沿用任务前情，其它模式一律关掉以免策略打架
        useContinuity = mode == TurnMode.START_WITH_CONTEXT;

        if (mode == TurnMode.UNCERTAIN) {
            agentId = null;
            if (Strings.isBlank(userReply)) {
                userReply = RuntimeMessage.ROUTE_UNCERTAIN.text();
            }
        }

        // 续跑、改题、取消、仅回复都不重新选人，清掉误带的 agentId
        if (mode == TurnMode.CONTINUE_TASK || mode == TurnMode.REPLACE_TASK || mode == TurnMode.CANCEL_TASK
            || mode == TurnMode.REPLY_ONLY) {
            agentId = null;
        }

        goalSummary = Strings.blankToNull(goalSummary);
        userReply = Strings.blankToNull(userReply);
        reason = Strings.blankToNull(reason);

        if (Objects.isNull(agentId) || Collections.isEmpty(candidates)) {
            return this;
        }

        boolean allowed = candidates.stream()
            .map(AgentSelection::getAgentId)
            .anyMatch(id -> Objects.equals(agentId, id));

        // 不在候选集合里的 id 不能当路由依据，避免启动不存在的 Agent
        if (!allowed) {
            agentId = null;
        }

        return this;
    }
}
