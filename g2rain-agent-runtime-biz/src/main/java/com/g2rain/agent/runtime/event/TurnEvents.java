package com.g2rain.agent.runtime.event;

import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.TurnStatus;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * {@link TurnEvent} 工厂：只产出 PROGRESS 与 RESULT 两种帧。
 *
 * @author alpha
 * @since 2026/8/27
 */
public final class TurnEvents {

    private TurnEvents() {

    }

    /**
     * 过程提示帧。
     *
     * @param turn 本轮
     * @param text 提示文案
     * @return PROGRESS 帧
     */
    public static TurnEvent progress(TurnScope turn, String text) {
        return new TurnEvent(TurnEventType.PROGRESS, turn.conversation().conversationId(), text, null,
            List.of(), List.of(), turn.traceId());
    }

    /**
     * 本轮结果帧；只有 WAITING 才带动作。
     *
     * @param turn        本轮
     * @param status      本轮结果状态；空按 FAILED
     * @param text        回复正文
     * @param actions     可执行动作
     * @param suggestions 候选答案
     * @return RESULT 帧
     */
    public static TurnEvent result(TurnScope turn, @Nullable TurnStatus status, @Nullable String text,
                                   @Nullable List<ResumeAction> actions, @Nullable List<String> suggestions) {
        TurnStatus outward = Objects.isNull(status) ? TurnStatus.FAILED : status;
        List<ResumeAction> allowed = outward == TurnStatus.WAITING ? copy(actions) : List.of();
        return new TurnEvent(TurnEventType.RESULT, turn.conversation().conversationId(), text, outward,
            allowed, copy(suggestions), turn.traceId());
    }

    /**
     * 本轮在编排外失败时的结果帧。
     *
     * @param traceId        本轮 ID
     * @param conversationId 会话 ID
     * @param text           对外文案
     * @return RESULT 帧
     */
    public static TurnEvent failed(String traceId, String conversationId, String text) {
        return new TurnEvent(TurnEventType.RESULT, conversationId, text, TurnStatus.FAILED, List.of(), List.of(), traceId);
    }

    private static <T> List<T> copy(@Nullable List<T> values) {
        return Objects.isNull(values) ? List.of() : List.copyOf(values);
    }
}
