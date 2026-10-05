package com.g2rain.agent.runtime.domain.state.task;


import org.jspecify.annotations.Nullable;

/**
 * 待确认的工具动作快照。
 * <p>
 * 典型场景：{@link CheckpointType#APPROVAL} 记下工具名与参数，用户
 * {@link ResumeAction#CONFIRM} 后再派发。
 * </p>
 * <p>恢复流程必须先校验 {@code argsHash} 与当前参数一致，再决定是否允许继续；该 record
 * 本身不会执行工具，也不会因用户确认而自动改变状态。</p>
 *
 * @param toolName 待执行的工具名
 * @param args     工具参数（通常为序列化后的 JSON 字符串）
 * @param argsHash 业务入参摘要；确认执行时须与现参一致
 * @author alpha
 * @since 2026/9/9
 */
public record PendingAction(String toolName, String args, @Nullable String argsHash) {

    /**
     * 创建尚未计算参数摘要的待确认动作。
     *
     * @param toolName 工具名
     * @param args     工具参数 JSON
     */
    public PendingAction(String toolName, String args) {
        this(toolName, args, null);
    }
}
