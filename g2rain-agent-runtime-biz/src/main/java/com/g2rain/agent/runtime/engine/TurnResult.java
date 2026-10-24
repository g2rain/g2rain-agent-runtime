package com.g2rain.agent.runtime.engine;

import com.g2rain.agent.runtime.domain.state.TurnStatus;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 本轮流水线返回给调用方的结论包。
 *
 * <p>对象同时携带会话和任务定位、展示状态、用户文案以及挂起恢复动作；它是响应模型，
 * 不替代 State 中的任务和证据数据。</p>
 *
 * @author alpha
 * @since 2026/8/28
 */
@Getter
@Setter
@NoArgsConstructor
public final class TurnResult {

    /**
     * 链路追踪 ID；便于把本轮结果与 SSE 流、日志对上。
     */
    private String traceId;

    /**
     * 本轮所属会话 ID。
     */
    private String conversationId;

    /**
     * 本轮关联任务 ID；尚未建任务或失败较早时可空。
     */
    private @Nullable String taskId;

    /**
     * 本轮生命周期结论，见 {@link TurnStatus}。
     */
    private TurnStatus status;

    /**
     * 回给用户的文案（成功摘要、挂起追问、失败原因等）；可空。
     */
    private @Nullable String content;

    /**
     * 失败时的业务错误码；仅 FAILED 且识别出业务异常时有值。
     */
    private @Nullable String errorCode;

    /**
     * 挂起场景下允许客户端发起的恢复动作列表。
     *
     * <p>例：{@code [REPLY, CONFIRM]}；非挂起时通常为空列表。</p>
     */
    private List<ResumeAction> allowedActions = new ArrayList<>();

    /**
     * ASK 快捷气泡文案；非挂起通常为空。
     */
    private List<String> suggestions = new ArrayList<>();

    /**
     * 创建没有任务定位的失败结果。
     *
     * @param conversationId 会话 ID
     * @param message        失败原因文案，写入 {@link #content}
     * @return status=FAILED 的结果
     */
    public static TurnResult fail(String conversationId, String message) {
        // 复用完整重载，保证两种失败结果使用同一套字段初始化规则。
        return fail(conversationId, null, message);
    }

    /**
     * 构造失败结果（可带任务 ID）。
     *
     * @param conversationId 会话 ID
     * @param taskId         任务 ID，可空
     * @param message        失败原因文案
     * @return status=FAILED 的结果
     */
    public static TurnResult fail(String conversationId, @Nullable String taskId, String message) {
        // 失败结果仍保留会话和任务定位，便于客户端和日志关联本次请求。
        TurnResult result = new TurnResult();
        result.setConversationId(conversationId);
        result.setTaskId(taskId);
        result.setStatus(TurnStatus.FAILED);
        result.setContent(message);
        return result;
    }

    /**
     * 创建完成结果，可带任务定位。
     *
     * @param conversationId 会话 ID
     * @param taskId         任务 ID，可空
     * @param message        回给用户的文案
     * @return status=DONE 的结果
     */
    public static TurnResult done(String conversationId, @Nullable String taskId, String message) {
        TurnResult result = new TurnResult();
        result.setConversationId(conversationId);
        result.setTaskId(taskId);
        result.setStatus(TurnStatus.DONE);
        result.setContent(message);
        return result;
    }
}
