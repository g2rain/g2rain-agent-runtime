package com.g2rain.agent.runtime.domain.state;


import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.task.EntityEntry;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TurnScope（本轮内存视图）：会话栅栏、活跃任务与尚未 flush 的改动。
 * <p>由 {@code RuntimeStateFacade.begin} 返回；写操作记在本对象上，经 flush / persist 提交。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public interface TurnScope {

    /**
     * @return 机构 id
     */
    String organId();

    /**
     * @return 当前用户 id
     */
    String userId();

    /**
     * @return 本轮 traceId（与网关 requestId 对应）
     */
    String traceId();

    /**
     * @return 本轮平台身份快照（begin 时拍定）
     */
    Platform platform();

    /**
     * @return 会话栅栏 Hash（version / lastTraceId / activeTaskId）
     */
    ConversationFence conversation();

    /**
     * 最近若干条不带 nodeId 的交互历史（用户消息与最终回复）。
     *
     * @param limit 最多条数
     * @return 从新到旧或按存储顺序的消息
     */
    List<DialogueMessage> dialogue(int limit);

    /**
     * 含中间文字的完整交互历史。
     *
     * @return 已加载及本轮追加的全部消息
     */
    List<DialogueMessage> allDialogue();

    /**
     * @return 已结束任务的回合摘要
     */
    List<TaskRecord> records();

    /**
     * @return 会话记忆实体索引，field = type:id
     */
    Map<String, EntityEntry> entities();

    /**
     * @return 活跃任务元数据；无任务为空
     */
    Optional<TaskMetadata> task();

    /**
     * @return 当前任务图；无任务时为空图
     */
    TaskGraph graph();

    /**
     * @return 当前断点（仍在等待用户）；未挂起或本轮已回应为空
     */
    Optional<TaskCheckpoint> checkpoint();

    /**
     * 本轮已被用户回应、已消费的断点。
     *
     * <p>节点被唤醒时断点即被消费：之后 {@link #checkpoint()} 为空，节点续跑用本方法读取待批写操作、
     * 追问字段等现场。例：确认续跑时从这里取 {@code pendingAction} 派发写工具。</p>
     *
     * @return 本轮已回应的断点；本轮未恢复为空
     */
    Optional<TaskCheckpoint> resumed();

    /**
     * @return 任务级共享值
     */
    Map<String, Sourced<?>> shared();

    /**
     * @return 任务前情；无任务或未生成时为空
     */
    Optional<Prior> prior();

    /**
     * @return 会话级共享值（任务结束不删）
     */
    Map<String, Sourced<?>> sessionShared();

    /**
     * 追加 AGENT 消息（中间文字、最终回复、确认卡片）。
     *
     * @param message 待追加消息
     */
    void append(DialogueMessage message);

    /**
     * 开新任务并设置 activeTaskId。
     *
     * @param metadata 任务元数据
     * @param prior    创建时生成的前情
     * @return 写入后的元数据
     */
    @SuppressWarnings("UnusedReturnValue")
    TaskMetadata start(TaskMetadata metadata, Prior prior);

    /**
     * 写入或扩展任务图（含委派加节点与边）。
     *
     * @param graph 任务图
     */
    void plan(TaskGraph graph);

    /**
     * 设置当前焦点节点 currentNodeId。
     *
     * @param nodeId 节点 id
     */
    void focus(String nodeId);

    /**
     * 更新图中节点状态。
     *
     * @param nodeId       节点 id
     * @param status       新状态
     * @param errorCode    失败码；成功为空
     * @param errorMessage 失败说明；成功为空
     */
    void mark(String nodeId, NodeStatus status, @Nullable String errorCode, @Nullable String errorMessage);

    /**
     * 挂起任务，写入断点与 suspendedAt。
     *
     * @param checkpoint 断点
     */
    void suspend(TaskCheckpoint checkpoint);

    /**
     * 消费当前断点并写恢复审计；没有当前断点时只把任务置为运行中。
     *
     * <p>同一轮重复调用只在第一次记录恢复动作，消费后的断点经 {@link #resumed()} 读取。</p>
     *
     * @param action    用户恢复动作
     * @param messageId 触发恢复的消息 id
     */
    void resume(ResumeAction action, String messageId);

    /**
     * 写任务级共享值（契约 outputs 声明 share=TASK）。
     *
     * @param key   共享键
     * @param value 带来源的值
     */
    void share(String key, Sourced<?> value);

    /**
     * 写会话级共享值（share=SESSION），任务结束不删除。
     *
     * @param key   共享键
     * @param value 带来源的值
     */
    void shareSession(String key, Sourced<?> value);

    /**
     * 标记任务结束；persist 成功后才删任务组、写 records。
     *
     * @param status       终态
     * @param finalMessage 收口文案
     */
    void close(TaskStatus status, String finalMessage);

    /**
     * 本轮失败收口：只把当前节点或仍在 RUNNING 的节点标 FAILED，关任务，追加一条 AGENT 回复。
     *
     * @param text      面向用户的统一文案
     * @param errorCode 节点错误码，写入失败节点便于排查；可空
     */
    void fail(String text, @Nullable String errorCode);
}
