package com.g2rain.agent.runtime.domain.state.task;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务级挂起检查点。
 *
 * <p>保存用户下一步动作、展示文案和恢复所需参数，与 {@link TaskStatus#SUSPENDED} 配套。
 * 焦点和 Agent 仍由 {@link TaskMetadata} 保存；完整交互历史与工具报文不在本对象。</p>
 *
 * @author alpha
 * @since 2026/9/9
 */
@Getter
@Setter
@NoArgsConstructor
public class TaskCheckpoint {

    /**
     * 检查点类型（恢复协议）：追问、审批或结果核实。
     */
    private CheckpointType type;

    /**
     * 停下等用户的原因；类型由它决定。
     */
    private HaltReason haltReason;

    /**
     * 检查点业务 id；事件与卡片用，缺省挂起时生成。
     */
    private @Nullable String checkpointId;

    /**
     * 挂起的节点；恢复动作只作用于该节点。
     */
    private @Nullable String nodeId;

    /**
     * 向用户展示的追问/确认文案。
     */
    private @Nullable String question;

    /**
     * 本检查点允许的恢复动作列表；入口必须按此列表校验用户动作。
     */
    private List<ResumeAction> allowedActions = new ArrayList<>();

    /**
     * ASK 快捷气泡文案（主站 UI）；调度与恢复不依赖。
     */
    private List<String> suggestions = new ArrayList<>();

    /**
     * 审批场景下待用户确认的工具动作；追问、核实场景通常为空。
     */
    private @Nullable PendingAction pendingAction;

    /**
     * FOLLOW_UP 场景待用户确认的参数路径（如 {@code title} / {@code status}）。
     *
     * <p>只在 {@link HaltReason#MISSING_INPUT} 时据此把用户回复认定为 USER 来源，避免 {@code forbidSynthetic} 死循环。</p>
     */
    private List<String> expectedFields = new ArrayList<>();
}
