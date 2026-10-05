package com.g2rain.agent.runtime.domain.react;


import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * ReActLoopResult（ReAct 循环结果）：单节点一次 ReAct 循环结束后的节点级结论。
 * <p>
 * 报告节点状态、说明文案、检查点类型、待确认动作，以及仍缺失的参数路径列表。
 * 例：节点挂起追问时 {@code nodeStatus=SUSPENDED, checkpointType=FOLLOW_UP, missingParameters=[orderId]}。
 *
 * @param nodeStatus        节点状态（运行中 / 完成 / 挂起 / 失败 / 等待委派等）
 * @param message           说明或追问文案；可空
 * @param checkpointType    挂起检查点类型；非挂起时通常为 {@code null}
 * @param pendingAction     审批类挂起时待用户确认的动作；可空
 * @param missingParameters 仍缺失的参数路径列表；可空
 * @param suggestions       ASK 快捷气泡文案；可空
 * @author alpha
 * @since 2026/8/21
 */
public record ReActLoopResult(NodeStatus nodeStatus, @Nullable String message, @Nullable CheckpointType checkpointType,
                              @Nullable PendingAction pendingAction, @Nullable List<String> missingParameters,
                              @Nullable List<String> suggestions) {

    /**
     * 仅带节点状态的结果（无消息、无挂起、无缺参）。
     *
     * @param nodeStatus 节点状态
     */
    public ReActLoopResult(NodeStatus nodeStatus) {
        this(nodeStatus, null, null, null, null, null);
    }

    /**
     * 带消息、检查点类型与待确认动作、不带缺参/建议的结果。
     *
     * @param nodeStatus     节点状态
     * @param message        说明文案；可空
     * @param checkpointType 检查点类型；可空
     * @param pendingAction  待确认动作；可空
     */
    public ReActLoopResult(NodeStatus nodeStatus, @Nullable String message, @Nullable CheckpointType checkpointType, @Nullable PendingAction pendingAction) {
        this(nodeStatus, message, checkpointType, pendingAction, null, null);
    }
}
