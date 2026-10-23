package com.g2rain.agent.runtime.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.g2rain.agent.runtime.domain.state.TurnStatus;
import com.g2rain.agent.runtime.domain.state.progress.StepView;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 发送给客户端的一帧事件；所有类型共用这一个结构，空字段不输出。
 *
 * <ul>
 *   <li>{@link TurnEventType#PROGRESS}：执行步骤，只带 {@code step}；同一步骤的开始与结束共用 {@code stepId}，
 *       客户端按 {@code stepId} 合并为时间线。</li>
 *   <li>{@link TurnEventType#TITLE}：会话标题，{@code text} 为标题；只在标题生成或变化时发。</li>
 *   <li>{@link TurnEventType#RESULT}：本轮结果，带 {@code status}、{@code text}；等待用户时另带
 *       {@code actions}，有候选答案时带 {@code suggestions}。每轮恰好一帧且为最后一帧。</li>
 * </ul>
 *
 * @param type           事件类型
 * @param conversationId 会话 ID；每帧都带，新会话从首帧即可拿到
 * @param text           RESULT 为回复正文，TITLE 为会话标题
 * @param status         本轮结果状态；仅 RESULT
 * @param actions        可执行动作；仅 RESULT 且 WAITING
 * @param suggestions    候选答案，点击后作为 REPLY 的 userInput 发回；仅 RESULT
 * @param createdAtMs    最终回复的发言时间 epoch ms，与入库一致；仅 RESULT
 * @param elapsedMs      本轮耗时毫秒，与入库一致；仅 RESULT
 * @param errorCode      失败时的业务错误码；仅 RESULT 且 FAILED
 * @param step           执行步骤快照；仅 PROGRESS
 * @param traceId        本轮追踪号；用于 SSE 路由，并下发给客户端在失败时展示
 * @author alpha
 * @since 2026/8/27
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record TurnEvent(TurnEventType type, String conversationId, @Nullable String text, @Nullable TurnStatus status,
                        List<ResumeAction> actions, List<String> suggestions, @Nullable Long createdAtMs,
                        @Nullable Integer elapsedMs, @Nullable String errorCode, @Nullable StepView step,
                        String traceId) {
}
