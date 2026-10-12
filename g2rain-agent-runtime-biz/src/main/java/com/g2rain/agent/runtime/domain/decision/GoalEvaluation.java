package com.g2rain.agent.runtime.domain.decision;


import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import org.jspecify.annotations.Nullable;

/**
 * GoalEvaluation（目标评估）：任务收口时的判定结果。
 * <p>
 * 给出任务状态、是否终局，并附带给用户的答复。
 * </p>
 *
 * @param taskStatus 评估后的任务状态（如 SUCCEEDED / FAILED）
 * @param terminal   是否终局（本 Task 不再继续推进）
 * @param summary    给用户的答复；可空
 * @author alpha
 * @since 2026/8/21
 */
public record GoalEvaluation(TaskStatus taskStatus, boolean terminal, @Nullable String summary) {

}
