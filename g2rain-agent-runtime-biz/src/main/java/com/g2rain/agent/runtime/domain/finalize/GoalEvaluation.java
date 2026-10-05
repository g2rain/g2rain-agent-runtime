package com.g2rain.agent.runtime.domain.finalize;


import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import org.jspecify.annotations.Nullable;

/**
 * GoalEvaluation（目标评估）：任务收口时对目标完成情况的判定结果。
 * <p>
 * 给出任务状态、是否终局，以及是否把事实提升进会话记忆、是否写出任务前情，并可附带摘要。
 * 例：成功终局且 {@code promoteFacts=true, writeContinuity=true} 表示落会话记忆并留下任务前情。
 *
 * @param taskStatus      评估后的任务状态（如 SUCCEEDED / FAILED）
 * @param terminal        是否终局（本 Task 不再继续推进）
 * @param promoteFacts    是否把可确认事实提升进会话记忆
 * @param writeContinuity 是否写出任务前情供后续任务衔接
 * @param summary         收口摘要文案；可空
 * @author alpha
 * @since 2026/8/21
 */
public record GoalEvaluation(TaskStatus taskStatus, boolean terminal, boolean promoteFacts, boolean writeContinuity,
                             @Nullable String summary) {

}
