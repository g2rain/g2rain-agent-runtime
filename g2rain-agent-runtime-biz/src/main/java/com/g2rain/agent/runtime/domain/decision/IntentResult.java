package com.g2rain.agent.runtime.domain.decision;


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
     * 分类器选中的 Agent 主键；收口时非选人模式或不在本轮候选内会被清空。
     */
    private @Nullable Long agentId;

    /**
     * 不启动或不能启动任务时直接返回给用户的文案；可空。
     */
    private @Nullable String userReply;

    /**
     * 规范化模型结论，使其可以安全地交给调度器校正。
     *
     * <p>清除文本首尾空白；任务前情开关由模式推导；只有选人模式保留 {@code agentId}，
     * 且必须来自本轮候选，避免启动不存在或不在候选内的 Agent。方法原地修改并返回当前实例。</p>
     *
     * @param candidates 本轮 Agent 候选；可空
     * @return this（便于链式调用）
     */
    public IntentResult complete(@Nullable List<AgentSelection> candidates) {
        // 只有「带前情再开」才沿用任务前情
        useContinuity = mode == TurnMode.START_WITH_CONTEXT;
        goalSummary = Strings.blankToNull(goalSummary);
        userReply = Strings.blankToNull(userReply);

        boolean listed = Objects.nonNull(agentId) && Collections.isNotEmpty(candidates)
            && candidates.stream().anyMatch(candidate -> Objects.equals(agentId, candidate.getAgentId()));
        if (Objects.isNull(mode) || !mode.selectsAgent() || !listed) {
            agentId = null;
        }

        return this;
    }
}
