package com.g2rain.agent.runtime.domain.state.task;


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * 活跃任务的身份、生命周期和并发控制元数据。
 * <p>
 * 调度与持久化靠这些字段认「这是哪一单、跑到哪、谁在管」；
 * 挂起细节不在这里，而在 {@link TaskCheckpoint}。
 * </p>
 *
 * @author alpha
 * @since 2026/9/9
 */
@Getter
@Setter
@NoArgsConstructor
public class TaskMetadata {

    /**
     * 所属会话 ID。
     */
    private String conversationId;

    /**
     * 本任务唯一 ID。
     */
    private String taskId;

    /**
     * 任务粗粒度生命周期状态。
     */
    private TaskStatus status;

    /**
     * 当前调度焦点节点 ID。
     * <p>挂起恢复、选波时优先看这个节点。</p>
     */
    private @Nullable String currentNodeId;

    /**
     * 执行本任务的 Agent 主键；尚未绑定可空。
     */
    private @Nullable Long agentId;

    /**
     * 任务目标摘要，给人/模型快速理解「这单要干什么」。
     */
    private @Nullable String goalSummary;

    /**
     * 终态对外文案（成功/失败收口时给用户看的一句话）。
     */
    private @Nullable String finalMessage;

    /**
     * 乐观版本号；状态写回时用来拒绝旧执行实例的覆盖。
     */
    private long version;

    /**
     * 最近一次更新时间戳（毫秒）；未写过可空。
     */
    private @Nullable Long updatedAt;

    /**
     * 最近一次进入挂起的时间；未挂起为空。审批恢复时可用它判断确认是否仍然有效。
     */
    private @Nullable Long suspendedAt;
}
