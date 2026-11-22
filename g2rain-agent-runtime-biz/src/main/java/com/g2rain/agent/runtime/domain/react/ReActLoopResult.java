package com.g2rain.agent.runtime.domain.react;


import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.state.task.HaltReason;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * ReActLoopResult（ReAct 循环结果）：单节点一次 ReAct 循环结束后的节点级结论。
 * <p>
 * 报告节点状态、说明文案、停顿原因、待确认动作，以及仍缺失的参数路径列表。
 * 例：节点挂起追问时 {@code nodeStatus=SUSPENDED, haltReason=MISSING_INPUT, missingParameters=[orderId]}。
 *
 * @param nodeStatus        节点状态（运行中 / 完成 / 挂起 / 失败 / 等待委派等）
 * @param message           说明或追问文案；可空
 * @param haltReason        挂起时停下等用户的原因；非挂起时为 {@code null}
 * @param pendingAction     审批类挂起时待用户确认的动作；可空
 * @param missingParameters 仍缺失的参数路径列表；可空
 * @param suggestions       ASK 快捷气泡文案；可空
 * @param errorCode         节点失败时的稳定错误码；非失败为 {@code null}
 * @author alpha
 * @since 2026/8/21
 */
public record ReActLoopResult(NodeStatus nodeStatus, @Nullable String message, @Nullable HaltReason haltReason,
                              @Nullable PendingAction pendingAction, @Nullable List<String> missingParameters,
                              @Nullable List<String> suggestions, @Nullable String errorCode) {

    /**
     * 带消息、停顿原因与待确认动作、不带缺参/建议/错误码的结果。
     *
     * @param nodeStatus    节点状态
     * @param message       说明文案；可空
     * @param haltReason    停顿原因；可空
     * @param pendingAction 待确认动作；可空
     */
    public ReActLoopResult(NodeStatus nodeStatus, @Nullable String message, @Nullable HaltReason haltReason,
                           @Nullable PendingAction pendingAction) {
        this(nodeStatus, message, haltReason, pendingAction, null, null, null);
    }

    /**
     * 带消息、停顿原因、待确认动作与错误码的结果。
     *
     * @param nodeStatus    节点状态
     * @param message       说明文案；可空
     * @param haltReason    停顿原因；可空
     * @param pendingAction 待确认动作；可空
     * @param errorCode     失败错误码；可空
     */
    public ReActLoopResult(NodeStatus nodeStatus, @Nullable String message, @Nullable HaltReason haltReason,
                           @Nullable PendingAction pendingAction, @Nullable String errorCode) {
        this(nodeStatus, message, haltReason, pendingAction, null, null, errorCode);
    }
}
