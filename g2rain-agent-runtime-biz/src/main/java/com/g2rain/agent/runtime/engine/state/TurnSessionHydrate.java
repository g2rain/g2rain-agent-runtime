package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.task.EntityEntry;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.NodeSpace;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.SpaceSpill;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.engine.state.repo.CallRepository;
import com.g2rain.agent.runtime.engine.state.repo.ConversationRepository;
import com.g2rain.agent.runtime.engine.state.repo.GraphRepository;
import com.g2rain.agent.runtime.engine.state.repo.PayloadRepository;
import com.g2rain.agent.runtime.engine.state.repo.TaskRepository;
import com.g2rain.agent.runtime.engine.store.KeyTtl;
import com.g2rain.agent.runtime.engine.store.RenewResult;
import com.g2rain.agent.runtime.engine.store.StateStore;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.vo.ChatConversationVo;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContextHolder;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 从热态和冷存储重建一次请求的会话视图。
 *
 * <p>加载过程优先读取 Redis，缺失时从持久化数据补齐，再恢复会话围栏、对话、历史记录、
 * 实体和活动任务；同时把上一执行实例遗留的中断状态规整为可恢复的事实。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Component
public class TurnSessionHydrate {

    /**
     * 反序列化实体、来源值和状态字段的 JSON 编解码器。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 实体 Hash 值的反序列化类型。
     */
    private static final TypeReference<EntityEntry> ENTITY = new TypeReference<>() {

    };

    /**
     * 共享值的来源包装类型。
     */
    private static final TypeReference<Sourced<?>> SOURCED = new TypeReference<>() {

    };

    /**
     * Redis 热状态访问组件。
     */
    private final StateStore store;

    /**
     * 会话冷数据回源。
     */
    private final ConversationRepository conversations;

    /**
     * 任务冷数据回源。
     */
    private final TaskRepository tasks;

    /**
     * 图与节点空间冷数据回源。
     */
    private final GraphRepository graphs;

    /**
     * 证据头冷数据回源。
     */
    private final CallRepository calls;

    /**
     * 节点空间超长字段溢出存储。
     */
    private final PayloadRepository payloads;

    /**
     * 状态窗口与 TTL 配置。
     */
    private final RuntimeProperties properties;

    /**
     * @param store          热态 Redis
     * @param conversations  会话仓储
     * @param tasks          任务仓储
     * @param graphs         图仓储
     * @param calls          工具调用仓储
     * @param payloads       报文仓储
     * @param properties     TTL 与窗口配置
     */
    public TurnSessionHydrate(StateStore store, ConversationRepository conversations, TaskRepository tasks,
                              GraphRepository graphs, CallRepository calls, PayloadRepository payloads,
                              RuntimeProperties properties) {
        this.store = store;
        this.conversations = conversations;
        this.tasks = tasks;
        this.graphs = graphs;
        this.calls = calls;
        this.payloads = payloads;
        this.properties = properties;
    }

    /**
     * 回灌会话热态并更新本轮租约，返回尚未登记当前用户消息的本轮视图。
     *
     * @param request 本轮请求
     * @return 本轮视图
     */
    public DefaultTurnScope load(TurnRequest request) {
        Long organ = PrincipalContextHolder.getOrganId();
        Long user = PrincipalContextHolder.getUserId();
        if (Objects.isNull(organ) || Objects.isNull(user)) {
            throw new BusinessException(RuntimeErrorCode.STATE_UNAVAILABLE);
        }

        String organId = Long.toString(organ);
        String userId = Long.toString(user);
        String traceId = RuntimeUtils.requireTraceId();
        String conversationId = request.getConversationId();
        SessionKeys keys = new SessionKeys(organId, conversationId);
        Map<String, String> hash;
        try {
            hash = store.hGetAll(keys.state());
        } catch (RuntimeException e) {
            throw new BusinessException(RuntimeErrorCode.STATE_UNAVAILABLE);
        }
        ChatConversationVo row = conversations.find(conversationId);
        ConversationFence fence = readFence(conversationId, hash);
        // Redis state 未命中：从 chat_conversation 回灌 version / activeTaskId / userId
        if (Collections.isEmpty(hash) && Objects.nonNull(row)) {
            Long rowUserId = row.getUserId();
            String rowUser = Objects.nonNull(rowUserId) ? Long.toString(rowUserId) : null;
            long version = Objects.requireNonNullElse(row.getStateVersion(), 0L);
            fence = new ConversationFence(conversationId, rowUser, row.getActiveTaskId(), version, null,
                System.currentTimeMillis());
        } else if (Strings.isBlank(fence.userId()) && Objects.nonNull(row)) {
            Long rowUserId = row.getUserId();
            if (Objects.nonNull(rowUserId)) {
                fence = new ConversationFence(conversationId, Long.toString(rowUserId), fence.activeTaskId(),
                    fence.version(), fence.lastTraceId(), fence.updatedAt());
            }
        }
        if (Strings.isBlank(fence.userId())) {
            fence = new ConversationFence(conversationId, userId, fence.activeTaskId(), fence.version(),
                fence.lastTraceId(), fence.updatedAt());
        }
        if (!Objects.equals(fence.userId(), userId)) {
            throw new BusinessException(RuntimeErrorCode.CONVERSATION_FORBIDDEN);
        }
        if (Objects.isNull(row)) {
            conversations.create(conversationId, user, request.getUserInput());
        }
        int dialogueMax = properties.getState().getDialogueMax();
        List<DialogueMessage> dialogue = readDialogue(keys, dialogueMax);
        if (Collections.isEmpty(dialogue)) {
            dialogue = conversations.loadDialogue(conversationId, dialogueMax);
        }
        List<TaskRecord> records = readRecords(keys);
        if (Collections.isEmpty(records)) {
            records = tasks.loadRecords(conversationId, properties.getState().getRecordsMax());
        }
        Map<String, EntityEntry> entities = readEntities(keys);
        Map<String, Sourced<?>> sessionShared = readShared(keys.sessionShared());
        if (Collections.isEmpty(sessionShared)) {
            sessionShared = new LinkedHashMap<>(conversations.loadSessionShared(conversationId));
        }
        TaskMetadata task = null;
        TaskGraph graph = new TaskGraph();
        TaskCheckpoint checkpoint = null;
        Map<String, Sourced<?>> shared = new LinkedHashMap<>();
        Prior prior = null;
        if (Strings.isNotBlank(fence.activeTaskId())) {
            String activeTaskId = fence.activeTaskId();
            task = HashCodec.toObject(store.hGetAll(keys.metadata(activeTaskId)), TaskMetadata.class);
            if (Objects.isNull(task)) {
                task = tasks.loadMetadata(activeTaskId);
            } else if (Strings.isBlank(task.getTaskId())) {
                task.setTaskId(activeTaskId);
            }
            if (Objects.nonNull(task) && Strings.isBlank(task.getConversationId())) {
                task.setConversationId(conversationId);
            }
            graph = HashCodec.graphOf(store.hGetAll(keys.graph(activeTaskId)));
            if (Objects.isNull(graph)) {
                graph = graphs.load(activeTaskId);
            }
            if (Objects.isNull(graph)) {
                graph = new TaskGraph();
            }
            checkpoint = JSON.str2obj(store.get(keys.checkpoint(activeTaskId)), TaskCheckpoint.class);
            if (Objects.isNull(checkpoint)) {
                checkpoint = tasks.loadCheckpoint(activeTaskId);
            }
            shared = readShared(keys.shared(activeTaskId));
            if (Collections.isEmpty(shared)) {
                shared = new LinkedHashMap<>(tasks.loadShared(activeTaskId));
            }
            prior = JSON.str2obj(store.get(keys.prior(activeTaskId)), Prior.class);
            if (Objects.isNull(prior)) {
                prior = tasks.loadPrior(activeTaskId);
            }
        }
        DefaultTurnScope turn = new DefaultTurnScope(organId, userId, traceId, fence, dialogue, records, entities, task,
            graph, checkpoint, shared, prior, sessionShared, dialogueMax);
        List<KeyTtl> extra = taskKeys(keys, turn, taskTtl(task));
        RenewResult renewed;
        try {
            renewed = store.renew(keys.state(), traceId, properties.getState().getConversationTtl(), keys.sessionShared(),
                extra, fence.version());
        } catch (RuntimeException e) {
            throw new BusinessException(RuntimeErrorCode.STATE_UNAVAILABLE);
        }
        turn.replaceFence(new ConversationFence(fence.conversationId(), fence.userId(), fence.activeTaskId(),
            renewed.version(), traceId, System.currentTimeMillis()));
        if (renewed.interrupted()) {
            // 上一轮 lastTraceId 未清：RUNNING 回 READY，STARTED 证据改 UNKNOWN
            tidyInterrupt(turn, keys);
        }

        return turn;
    }

    /**
     * 中断规整：RUNNING 节点回 READY；STARTED 证据改 UNKNOWN。
     *
     * @param turn 本轮视图
     * @param keys Redis key
     */
    private void tidyInterrupt(DefaultTurnScope turn, SessionKeys keys) {
        turn.task().filter(meta -> Objects.equals(meta.getStatus(), TaskStatus.RUNNING)).ifPresent(meta -> {
            turn.graph().getNodes().stream()
                .filter(node -> Objects.equals(node.getStatus(), NodeStatus.RUNNING))
                .forEach(node -> turn.mark(node.getNodeId(), NodeStatus.READY, null, null));
            turn.graph().getNodes().forEach(node -> rewriteStarted(keys, meta.getTaskId(), node, turn));
        });
    }

    /**
     * STARTED → UNKNOWN，挂到本轮节点视图，随后 flush。
     *
     * @param keys   Redis key
     * @param taskId 任务 id
     * @param node   图节点
     * @param turn   本轮视图
     */
    private void rewriteStarted(SessionKeys keys, String taskId, TaskNode node, DefaultTurnScope turn) {
        String evidenceKey = keys.evidence(taskId, node.getNodeId());
        store.hGetAll(evidenceKey).forEach((id, raw) -> {
            EvidenceHead head = JSON.str2obj(raw, EvidenceHead.class);
            if (Objects.isNull(head) || !Objects.equals(head.status(), EvidenceStatus.STARTED)) {
                return;
            }
            EvidenceHead unknown = head.withStatus(EvidenceStatus.UNKNOWN);
            DefaultNodeScope opened = openSpace(turn, keys, node.getNodeId());
            opened.addEvidence(unknown);
        });
    }

    /**
     * 打开节点空间（hydrate / Facade.open 共用）。
     *
     * @param turn   本轮
     * @param keys   key
     * @param nodeId 节点 id
     * @return 节点视图
     */
    public DefaultNodeScope openSpace(DefaultTurnScope turn, SessionKeys keys, String nodeId) {
        return turn.node(nodeId).orElseGet(() -> {
            String taskId = turn.task().map(TaskMetadata::getTaskId).orElse(null);
            NodeSpace space = new NodeSpace(Map.of(), Map.of(), Map.of(), 0, 0, List.of());
            List<EvidenceHead> evidence = new ArrayList<>();
            if (Strings.isNotBlank(taskId)) {
                NodeSpace loaded = HashCodec.toObject(expandSpace(store.hGetAll(keys.space(taskId, nodeId))),
                    NodeSpace.class);
                if (Objects.isNull(loaded)) {
                    loaded = graphs.loadSpace(taskId, nodeId);
                }
                if (Objects.nonNull(loaded)) {
                    space = loaded;
                }
                store.hGetAll(keys.evidence(taskId, nodeId)).values().forEach(raw -> {
                    EvidenceHead head = JSON.str2obj(raw, EvidenceHead.class);
                    if (Objects.nonNull(head)) {
                        evidence.add(head);
                    }
                });
                if (evidence.isEmpty()) {
                    evidence.addAll(calls.loadNodeEvidence(taskId, nodeId));
                }
            }
            DefaultNodeScope node = new DefaultNodeScope(turn, nodeId, space.inputs(), space.outputs(), space.state(),
                evidence, space.actions(), space.rounds(), space.tokens());
            turn.attach(node);
            return node;
        });
    }

    /**
     * 任务组续期 key。
     *
     * @param keys 会话 key
     * @param turn 本轮
     * @param ttl  任务 TTL
     * @return extraKeys
     */
    public List<KeyTtl> taskKeys(SessionKeys keys, DefaultTurnScope turn, Duration ttl) {
        return turn.task().map(meta -> {
            String taskId = meta.getTaskId();
            long taskMs = ttl.toMillis();
            long sentinel = Math.max(1L, taskMs - properties.getState().getSentinelGap().toMillis());
            List<KeyTtl> extras = new ArrayList<>();
            extras.add(new KeyTtl(keys.metadata(taskId), sentinel));
            extras.add(new KeyTtl(keys.graph(taskId), taskMs));
            extras.add(new KeyTtl(keys.checkpoint(taskId), taskMs));
            extras.add(new KeyTtl(keys.shared(taskId), taskMs));
            extras.add(new KeyTtl(keys.prior(taskId), taskMs));
            extras.add(new KeyTtl(keys.writes(taskId), taskMs));
            turn.graph().getNodes().forEach(node -> {
                extras.add(new KeyTtl(keys.space(taskId, node.getNodeId()), taskMs));
                extras.add(new KeyTtl(keys.evidence(taskId, node.getNodeId()), taskMs));
            });
            return extras;
        }).orElse(List.of());
    }

    /**
     * 按任务粗状态选 TTL。
     *
     * @param task 活跃任务；无任务为空
     * @return TTL
     */
    public Duration taskTtl(@Nullable TaskMetadata task) {
        if (Objects.nonNull(task) && Objects.equals(task.getStatus(), TaskStatus.SUSPENDED)) {
            return properties.getState().getTaskTtlSuspended();
        }
        return properties.getState().getTaskTtlRunning();
    }

    /**
     * NodeSpace → Redis Hash；单字段超过 {@code spaceFieldMaxBytes} 时写 OUTPUT 并用占位替换。
     *
     * @param taskId 任务 id
     * @param nodeId 节点 id
     * @param space  节点空间
     * @return 可写入 Redis 的 Hash
     */
    public Map<String, String> encodeSpace(String taskId, String nodeId, NodeSpace space) {
        Map<String, String> hash = HashCodec.toHash(space);
        int max = properties.getState().getSpaceFieldMaxBytes();
        Map<String, String> out = new LinkedHashMap<>();
        hash.forEach((field, raw) -> out.put(field, spillField(taskId, nodeId, field, raw, max)));
        return out;
    }

    /**
     * 把 OUTPUT 占位还原成完整字段 JSON，再交给 {@link HashCodec#toObject}。
     *
     * @param hash Redis hGetAll
     * @return 还原后的 Hash
     */
    public Map<String, String> expandSpace(Map<String, String> hash) {
        if (Collections.isEmpty(hash)) {
            return hash;
        }
        Map<String, String> out = new LinkedHashMap<>();
        hash.forEach((field, raw) -> out.put(field, expandField(raw)));
        return out;
    }

    /**
     * 超长字段落库并换成 {@link SpaceSpill} JSON。
     *
     * @param taskId 任务 id
     * @param nodeId 节点 id
     * @param field  NodeSpace 字段名
     * @param raw    原始 JSON 文本
     * @param max    允许留在 Redis 的最大字节数
     * @return 原文本或占位 JSON
     */
    private String spillField(String taskId, String nodeId, String field, String raw, int max) {
        if (Strings.isBlank(raw) || raw.getBytes(StandardCharsets.UTF_8).length <= max) {
            return raw;
        }
        String payloadId = RuntimeUtils.uuid();
        try {
            payloads.saveSpaceField(payloadId, taskId, nodeId, field, PrincipalContextHolder.getTraceId(), raw);
        } catch (RuntimeException e) {
            throw new BusinessException(RuntimeErrorCode.STATE_WRITE_FAILED);
        }
        return JSON.obj2str(SpaceSpill.of(payloadId));
    }

    /**
     * 占位则读 OUTPUT；读不到则失败关闭。
     *
     * @param raw Redis 字段文本
     * @return 完整 JSON；非占位则原样返回
     */
    private String expandField(String raw) {
        if (Strings.isBlank(raw) || !raw.trim().startsWith("{")) {
            return raw;
        }
        SpaceSpill spill = JSON.str2obj(raw, SpaceSpill.class);
        if (Objects.isNull(spill) || !spill.valid()) {
            return raw;
        }
        String body = payloads.loadSpaceField(spill.payloadId());
        if (Strings.isBlank(body)) {
            throw new BusinessException(RuntimeErrorCode.STATE_UNAVAILABLE);
        }
        return body;
    }

    /**
     * 从 Redis 列表读取交互历史，时间正序。
     *
     * @param keys 会话 Redis key
     * @param max  最多条数
     * @return 交互历史；空列表表示没有记录
     */
    private List<DialogueMessage> readDialogue(SessionKeys keys, int max) {
        List<String> raw = new ArrayList<>(store.lRange(keys.dialogue(), 0, max - 1L));
        java.util.Collections.reverse(raw);
        List<DialogueMessage> dialogue = new ArrayList<>();
        raw.forEach(item -> {
            DialogueMessage message = JSON.str2obj(item, DialogueMessage.class);
            if (Objects.nonNull(message)) {
                dialogue.add(message);
            }
        });
        return dialogue;
    }

    /**
     * 从 Redis 读取已结束任务记录，时间正序。
     *
     * @param keys 会话 Redis key
     * @return 任务记录；空列表表示没有记录
     */
    private List<TaskRecord> readRecords(SessionKeys keys) {
        List<String> raw = new ArrayList<>(store.lRange(keys.records(), 0, properties.getState().getRecordsMax() - 1L));
        java.util.Collections.reverse(raw);
        List<TaskRecord> records = new ArrayList<>();
        raw.forEach(item -> {
            TaskRecord record = JSON.str2obj(item, TaskRecord.class);
            if (Objects.nonNull(record)) {
                records.add(record);
            }
        });
        return records;
    }

    /**
     * 从 Redis Hash 读取会话实体索引。
     *
     * @param keys 会话 Redis key
     * @return {@code type:id →} 实体；空 Map 表示没有实体
     */
    private Map<String, EntityEntry> readEntities(SessionKeys keys) {
        Map<String, EntityEntry> entities = new LinkedHashMap<>();
        store.hGetAll(keys.entities()).forEach((field, raw) -> {
            EntityEntry entry = JSON.str2obj(raw, ENTITY);
            entities.put(field, entry);
        });
        return entities;
    }

    /**
     * 从 Redis Hash 读取任务共享或会话共享。
     *
     * @param key Redis Hash key
     * @return 带源值；空 Map 表示没有共享字段
     */
    private Map<String, Sourced<?>> readShared(String key) {
        Map<String, Sourced<?>> shared = new LinkedHashMap<>();
        store.hGetAll(key).forEach((field, raw) -> {
            Sourced<?> value = JSON.str2obj(raw, SOURCED);
            shared.put(field, value);
        });
        return shared;
    }

    /**
     * Redis {@code state} Hash → {@link ConversationFence}（字段名即 record 属性，不按字符串抠 key）。
     *
     * @param conversationId 会话 id（在 key 上，不在 Hash 里）
     * @param hash           hGetAll 结果
     * @return 栅栏；{@code userId} 以 Hash 为准，缺字段为空
     */
    private ConversationFence readFence(String conversationId, Map<String, String> hash) {
        ConversationFence parsed = HashCodec.toObject(hash, ConversationFence.class);
        if (Objects.isNull(parsed)) {
            return new ConversationFence(conversationId, null, null, 0L, null, System.currentTimeMillis());
        }
        long updatedAt = parsed.updatedAt() == 0L ? System.currentTimeMillis() : parsed.updatedAt();
        return new ConversationFence(conversationId, parsed.userId(), parsed.activeTaskId(), parsed.version(),
            parsed.lastTraceId(), updatedAt);
    }
}
