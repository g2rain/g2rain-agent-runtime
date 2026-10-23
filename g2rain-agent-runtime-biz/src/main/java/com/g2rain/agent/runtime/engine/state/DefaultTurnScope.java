package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.Platform;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.dialogue.MessageType;
import com.g2rain.agent.runtime.domain.state.progress.TurnProgress;
import com.g2rain.agent.runtime.domain.state.task.ClosedTask;
import com.g2rain.agent.runtime.domain.state.task.EntityEntry;
import com.g2rain.agent.runtime.domain.state.task.NodeSpace;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 本轮内存视图：会话、任务、交互历史与节点现场都先改这里，再经 Facade flush / persist 提交。
 *
 * <p>访问器返回快照；写入只影响本对象。热态 Redis 与库表不由本类直接访问。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public final class DefaultTurnScope implements TurnScope {

    /**
     * 机构 id。
     */
    private final String organId;

    /**
     * 当前用户 id。
     */
    private final String userId;

    /**
     * 当前请求的链路追踪标识，用于关联消息、事件和状态变更。
     */
    private final String traceId;

    /**
     * 会话栅栏。
     */
    private ConversationFence fence;

    /**
     * 会话对话（含中间文字）及刷写游标。
     */
    private final DialogueLog dialogue;

    /**
     * 已结束任务记录。
     */
    private final List<TaskRecord> records;

    /**
     * 实体索引。
     */
    private final Map<String, EntityEntry> entities;

    /**
     * 活跃任务；无任务为空。
     */
    private @Nullable TaskMetadata task;

    /**
     * 任务图。
     */
    private TaskGraph graph;

    /**
     * 当前断点。
     */
    private @Nullable TaskCheckpoint checkpoint;

    /**
     * 本轮已被用户回应、已消费的断点。
     */
    private @Nullable TaskCheckpoint resumed;

    /**
     * 任务级共享值。
     */
    private final Map<String, Sourced<?>> shared;

    /**
     * 任务前情；无前情为 {@code null}。
     */
    private @Nullable Prior prior;

    /**
     * 会话级共享值。
     */
    private final Map<String, Sourced<?>> sessionShared;

    /**
     * 恢复动作审计（persist 时写入 resume 表）。
     */
    private @Nullable ResumeAction lastResumeAction;

    /**
     * 触发恢复的消息的唯一标识。
     */
    private @Nullable String lastResumeMessageId;

    /**
     * 被新任务顶替的旧终态，persist 时一并写入。
     */
    private final List<ClosedTask> closedTasks = new ArrayList<>();

    /**
     * 本轮打开过的节点。
     */
    private final Map<String, DefaultNodeScope> nodes = new LinkedHashMap<>();

    /**
     * 本轮写入、尚未 flush 的证据报文。
     */
    private final Map<String, JsonNode> payloads = new LinkedHashMap<>();

    /**
     * 已经成功 flush 过的报文 id，终态删 Redis 仍要带上。
     */
    private final Set<String> flushedPayloadIds = new LinkedHashSet<>();

    /**
     * 本轮平台身份快照；begin 前默认为空。
     */
    private Platform platform = Platform.empty();

    /**
     * 本轮开始时间（epoch ms），用于计算最终回复耗时。
     */
    private final long startedAt = System.currentTimeMillis();

    /**
     * 本轮最终回复；追加时记录。
     */
    private @Nullable DialogueMessage reply;

    /**
     * 本轮执行过程。
     */
    private final TurnProgress progress = new TurnProgress();

    /**
     * @param organId       机构 id
     * @param userId        用户 id
     * @param traceId       本轮 traceId
     * @param fence         会话栅栏（含本轮 version）
     * @param dialogue      已加载的交互历史
     * @param records       已加载的任务记录
     * @param entities      已加载的实体
     * @param task          活跃任务
     * @param graph         任务图；无任务时传空图
     * @param checkpoint    断点
     * @param shared        任务共享值
     * @param prior         任务前情；可空
     * @param sessionShared 会话共享值
     * @param dialogueMax   交互历史保留条数
     */
    public DefaultTurnScope(String organId, String userId, String traceId, ConversationFence fence,
                            List<DialogueMessage> dialogue, List<TaskRecord> records,
                            Map<String, EntityEntry> entities, @Nullable TaskMetadata task, TaskGraph graph,
                            @Nullable TaskCheckpoint checkpoint, Map<String, Sourced<?>> shared, @Nullable Prior prior,
                            Map<String, Sourced<?>> sessionShared, int dialogueMax) {
        this.organId = organId;
        this.userId = userId;
        this.traceId = traceId;
        this.fence = fence;
        this.dialogue = new DialogueLog(dialogue, dialogueMax);
        this.records = new ArrayList<>(records);
        this.entities = new LinkedHashMap<>(entities);
        this.task = task;
        this.graph = graph;
        this.checkpoint = checkpoint;
        this.shared = new LinkedHashMap<>(shared);
        this.prior = prior;
        this.sessionShared = new LinkedHashMap<>(sessionShared);
    }

    /**
     * begin 时挂上本轮平台快照。
     *
     * @param platform 本轮平台上下文；可空则写入空快照
     */
    public void attachPlatform(Platform platform) {
        this.platform = Objects.requireNonNullElseGet(platform, Platform::empty);
    }

    /**
     * @return 机构 id
     */
    @Override
    public String organId() {
        return organId;
    }

    /**
     * @return 用户 id
     */
    @Override
    public String userId() {
        return userId;
    }

    /**
     * @return 本轮链路标识
     */
    @Override
    public String traceId() {
        return traceId;
    }

    /**
     * @return 本轮平台快照
     */
    @Override
    public Platform platform() {
        return platform;
    }

    /**
     * @return 会话栅栏
     */
    @Override
    public ConversationFence conversation() {
        return fence;
    }

    /**
     * 返回用于路由和提示的对话窗口。
     *
     * <p>节点内部的 Agent 消息不进入这个窗口，避免把执行细节误当成用户与 Agent
     * 的会话内容。窗口只保留最后 {@code limit} 条可路由消息；传入负数时按空窗口处理。</p>
     *
     * @param limit 最多返回的消息数量
     * @return 不可变的对话窗口
     */
    @Override
    public List<DialogueMessage> dialogue(int limit) {
        return dialogue.window(limit);
    }

    /**
     * 含中间文字的完整对话，供 flush 落 Redis。
     *
     * @return 本轮已加载及追加的全部消息
     */
    @Override
    public List<DialogueMessage> allDialogue() {
        return dialogue.all();
    }

    /**
     * 返回会话中的已结束任务记录。
     *
     * @return 不可变的任务记录快照
     */
    @Override
    public List<TaskRecord> records() {
        return List.copyOf(records);
    }

    /**
     * 返回会话实体索引的只读快照。
     *
     * @return 实体键到实体条目的映射
     */
    @Override
    public Map<String, EntityEntry> entities() {
        return Map.copyOf(entities);
    }

    /**
     * persist 实体双写用的可变索引。
     *
     * @return 实体索引（可变，键为 {@code type:id}）
     */
    Map<String, EntityEntry> entityIndex() {
        return entities;
    }

    /**
     * @return 当前任务元数据；尚未建任务时为空
     */
    @Override
    public Optional<TaskMetadata> task() {
        return Objects.isNull(task) ? Optional.empty() : Optional.of(task);
    }

    /**
     * @return 当前任务图；无图时为空图
     */
    @Override
    public TaskGraph graph() {
        return graph;
    }

    /**
     * @return 当前检查点；未挂起时为空
     */
    @Override
    public Optional<TaskCheckpoint> checkpoint() {
        return Objects.isNull(checkpoint) ? Optional.empty() : Optional.of(checkpoint);
    }

    /**
     * @return 本轮已回应的断点；本轮未恢复为空
     */
    @Override
    public Optional<TaskCheckpoint> resumed() {
        return Objects.isNull(resumed) ? Optional.empty() : Optional.of(resumed);
    }

    /**
     * @return 任务级共享任务记忆的只读快照
     */
    @Override
    public Map<String, Sourced<?>> shared() {
        return Map.copyOf(shared);
    }

    /**
     * @return 任务前情；没有衔接包时为空
     */
    @Override
    public Optional<Prior> prior() {
        return Objects.isNull(prior) ? Optional.empty() : Optional.of(prior);
    }

    /**
     * @return 会话级共享记忆的只读快照
     */
    @Override
    public Map<String, Sourced<?>> sessionShared() {
        return Map.copyOf(sessionShared);
    }

    /**
     * 把一条消息追加到本轮内存对话，并按配置裁剪最早消息。
     *
     * <p>裁剪时同步调整 Redis 和 MySQL 的已刷写计数，避免后续增量刷写跳过仍需保留的
     * 消息。Agent 最终回复追加时写入本轮耗时并记为 {@link #reply()}。</p>
     *
     * @param message 待追加消息
     */
    @Override
    public void append(DialogueMessage message) {
        if (message.getRole() == DialogueRole.AGENT
            && message.getType() == MessageType.AGENT_REPLY
            && Strings.isBlank(message.getNodeId())) {
            message.setElapsedMs(Math.toIntExact(Math.max(0, message.getCreatedAt() - startedAt)));
            reply = message;
        }
        dialogue.append(message);
    }

    @Override
    public Optional<DialogueMessage> reply() {
        return Objects.isNull(reply) ? Optional.empty() : Optional.of(reply);
    }

    @Override
    public TurnProgress progress() {
        return progress;
    }

    /**
     * 开始一个新任务并关闭当前活跃任务在本轮内的冷数据快照。
     *
     * <p>新任务会重置任务图、断点、任务共享值和节点现场，但保留会话级对话、实体和
     * 会话共享值；栅栏中的 activeTaskId 同步切换到新任务。</p>
     *
     * @param metadata 新任务元数据
     * @param prior    新任务使用的前情快照
     * @return 已设置为当前任务的元数据
     */
    @Override
    public TaskMetadata start(TaskMetadata metadata, Prior prior) {
        if (Objects.nonNull(this.task)) {
            Map<String, NodeSpace> spaces = new LinkedHashMap<>();
            nodes.forEach((id, node) -> spaces.put(id, new NodeSpace(
                node.inputs(), node.outputs(), node.state(), node.rounds(), node.tokens(), node.actions()
            )));
            closedTasks.add(new ClosedTask(this.task, this.graph, Map.copyOf(this.shared), this.prior,
                List.copyOf(allPayloadIds()), spaces));
        }
        this.task = metadata;
        this.prior = prior;
        this.graph = new TaskGraph();
        this.checkpoint = null;
        this.resumed = null;
        this.shared.clear();
        nodes.clear();
        payloads.clear();
        flushedPayloadIds.clear();
        // 开新任务：栅栏记下 activeTaskId
        fence = new ConversationFence(fence.conversationId(), fence.userId(), metadata.getTaskId(),
            fence.version(), fence.lastTraceId(), System.currentTimeMillis());
        return metadata;
    }

    /**
     * 替换当前任务图。
     *
     * @param graph 规划得到的任务图
     */
    @Override
    public void plan(TaskGraph graph) {
        this.graph = graph;
    }

    /**
     * 设置任务当前聚焦的节点。
     *
     * @param nodeId 节点 id；没有活跃任务时忽略
     */
    @Override
    public void focus(String nodeId) {
        if (Objects.nonNull(task)) {
            task.setCurrentNodeId(nodeId);
        }
    }

    /**
     * 更新任务图中指定节点的状态和错误信息，并记下首次运行与进入终态的时间。
     *
     * @param nodeId       节点 id
     * @param status       新状态
     * @param errorCode    错误码；成功或清除错误时可为空
     * @param errorMessage 错误说明；没有错误时可为空
     */
    @Override
    public void mark(String nodeId, NodeStatus status, @Nullable String errorCode, @Nullable String errorMessage) {
        TaskNode node = graph.find(nodeId);
        if (Objects.isNull(node)) {
            return;
        }
        node.setStatus(status);
        node.setErrorCode(errorCode);
        node.setErrorMessage(errorMessage);
        long now = System.currentTimeMillis();
        if (status == NodeStatus.RUNNING && Objects.isNull(node.getStartedAt())) {
            node.setStartedAt(now);
        }
        if (status.isTerminal()) {
            node.setFinishedAt(now);
        }
    }

    /**
     * 写入当前断点并将活跃任务标记为挂起。
     *
     * @param checkpoint 等待用户输入、确认或恢复处理所需的断点
     */
    @Override
    public void suspend(TaskCheckpoint checkpoint) {
        this.checkpoint = checkpoint;
        if (Objects.nonNull(task)) {
            task.setStatus(TaskStatus.SUSPENDED);
            task.setSuspendedAt(System.currentTimeMillis());
        }
    }

    /**
     * 消费当前断点并记录恢复审计信息；没有当前断点时只把任务置为运行中。
     *
     * @param action    用户选择的恢复动作
     * @param messageId 触发恢复的消息 id
     */
    @Override
    public void resume(ResumeAction action, String messageId) {
        if (Objects.nonNull(checkpoint)) {
            this.resumed = checkpoint;
            this.checkpoint = null;
            this.lastResumeAction = action;
            this.lastResumeMessageId = messageId;
        }
        if (Objects.nonNull(task)) {
            task.setStatus(TaskStatus.RUNNING);
        }
    }

    /**
     * persist 写恢复审计用。
     *
     * @return 本轮 resume 动作；未恢复为空
     */
    public Optional<ResumeAction> lastResumeAction() {
        return Objects.isNull(lastResumeAction) ? Optional.empty() : Optional.of(lastResumeAction);
    }

    /**
     * persist 写恢复审计用。
     *
     * @return 触发恢复的消息 id
     */
    public Optional<String> lastResumeMessageId() {
        return Objects.isNull(lastResumeMessageId) ? Optional.empty() : Optional.of(lastResumeMessageId);
    }

    /**
     * 写入任务级共享值。
     *
     * @param key   任务内共享键
     * @param value 带来源信息的共享值
     */
    @Override
    public void share(String key, Sourced<?> value) {
        shared.put(key, value);
    }

    /**
     * 写入会话级共享值。
     *
     * @param key   跨任务共享键
     * @param value 带来源信息的共享值
     */
    @Override
    public void shareSession(String key, Sourced<?> value) {
        sessionShared.put(key, value);
    }

    /**
     * 把活跃任务标为终态并记下最终文案；会话栅栏的 activeTaskId 等到 persist 成功后再清。
     *
     * @param status       任务终态
     * @param finalMessage 回给用户的收口文案
     */
    @Override
    public void close(TaskStatus status, String finalMessage) {
        if (Objects.isNull(task)) {
            return;
        }
        task.setStatus(status);
        task.setFinalMessage(finalMessage);
        // persist 成功后再清 activeTaskId；本轮先记下终态
    }

    /**
     * 本轮失败：当前节点与仍 RUNNING 的节点标 FAILED，任务 close 为 FAILED，并追加 AGENT 回复。
     *
     * @param text      失败文案
     * @param errorCode 稳定错误码；可空
     */
    @Override
    public void fail(String text, @Nullable String errorCode) {
        String currentId = Objects.nonNull(task) ? task.getCurrentNodeId() : null;
        if (Strings.isNotBlank(currentId)) {
            mark(currentId, NodeStatus.FAILED, errorCode, text);
        }
        graph.getNodes().forEach(node -> {
            if (node.getStatus() == NodeStatus.RUNNING
                && !Objects.equals(node.getNodeId(), currentId)) {
                mark(node.getNodeId(), NodeStatus.FAILED, errorCode, text);
            }
        });
        close(TaskStatus.FAILED, text);
        if (Strings.isBlank(text)) {
            return;
        }
        DialogueMessage agent = new DialogueMessage(DialogueRole.AGENT, text);
        agent.setMessageId(RuntimeUtils.uuid());
        agent.setTraceId(traceId);
        agent.setType(MessageType.AGENT_REPLY);
        agent.setTaskId(Objects.nonNull(task) ? task.getTaskId() : null);
        append(agent);
    }

    /**
     * persist 成功后清空 activeTaskId。
     */
    public void clearActiveTask() {
        fence = new ConversationFence(fence.conversationId(), fence.userId(), null,
            fence.version(), fence.lastTraceId(), System.currentTimeMillis());
        this.task = null;
    }

    /**
     * 用 renew 后的栅栏替换内存中的 version / lastTraceId。
     *
     * @param fence 新栅栏
     */
    public void replaceFence(ConversationFence fence) {
        this.fence = fence;
    }

    /**
     * 正常收尾：清空 lastTraceId。
     */
    public void clearLastTrace() {
        fence = new ConversationFence(fence.conversationId(), fence.userId(), fence.activeTaskId(),
            fence.version(), null, System.currentTimeMillis());
    }

    /**
     * 登记本轮打开的节点，供 flush 写 space / evidence。
     *
     * @param node 节点视图
     */
    public void attach(DefaultNodeScope node) {
        nodes.put(node.nodeId(), node);
    }

    /**
     * @return 本轮打开过的节点
     */
    public Map<String, DefaultNodeScope> nodes() {
        return nodes;
    }

    /**
     * 记下证据报文，与 EvidenceHead 同一次 flush。
     *
     * @param evidenceId 证据 id
     * @param payload    报文；空则不写
     */
    public void putPayload(String evidenceId, @Nullable JsonNode payload) {
        if (Objects.nonNull(payload) && !payload.isNull()) {
            payloads.put(evidenceId, payload);
        }
    }

    /**
     * @return 本轮证据报文
     */
    public Map<String, JsonNode> payloads() {
        return payloads;
    }

    /**
     * 本轮已 flush 与尚未 flush 的证据报文 id 并集。
     *
     * @return 报文 id 列表
     */
    public List<String> allPayloadIds() {
        Set<String> ids = new LinkedHashSet<>(flushedPayloadIds);
        ids.addAll(payloads.keySet());
        return List.copyOf(ids);
    }

    /**
     * Redis flush 成功后：已写入 id 并入 flushed 集合，并清空本轮待写报文。
     */
    public void markPayloadsFlushed() {
        flushedPayloadIds.addAll(payloads.keySet());
        payloads.clear();
    }

    /**
     * 尚未 LPUSH 的对话，包括入口登记的用户消息和本轮追加的 Agent 消息。
     *
     * @return 待写入 Redis List 的消息
     */
    public List<DialogueMessage> pendingDialogue() {
        return dialogue.pendingFlush();
    }

    /**
     * 尚未写入 MySQL 的对话（Redis flush 后仍保留，直到 persist 成功）。
     *
     * @return 待 insert 的消息
     */
    public List<DialogueMessage> pendingPersistDialogue() {
        return dialogue.pendingPersist();
    }

    /**
     * Redis flush 成功后推进已写入游标。
     */
    public void markDialogueFlushed() {
        dialogue.markFlushed();
    }

    /**
     * MySQL persist 成功后推进已落库游标。
     */
    public void markDialoguePersisted() {
        dialogue.markPersisted();
    }

    /**
     * 本轮被新任务顶替的旧终态。
     *
     * @return 快照列表
     */
    public List<ClosedTask> closedTasks() {
        return List.copyOf(closedTasks);
    }

    /**
     * 按节点取已打开视图；未打开为空。
     *
     * @param nodeId 节点 id
     * @return 节点视图
     */
    public Optional<DefaultNodeScope> node(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }
}
