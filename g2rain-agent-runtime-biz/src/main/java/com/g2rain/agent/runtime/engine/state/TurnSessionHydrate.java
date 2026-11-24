package com.g2rain.agent.runtime.engine.state;



import lombok.extern.slf4j.Slf4j;
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
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.engine.state.repo.CallRepository;
import com.g2rain.agent.runtime.engine.state.repo.ConversationRepository;
import com.g2rain.agent.runtime.engine.state.repo.GraphRepository;
import com.g2rain.agent.runtime.engine.state.repo.TaskRepository;
import com.g2rain.agent.runtime.engine.store.KeyTtl;
import com.g2rain.agent.runtime.engine.store.RenewResult;
import com.g2rain.agent.runtime.engine.store.StateStore;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.vo.ChatConversationVo;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContextHolder;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 从热态和冷存储重建一次请求的会话视图。
 *
 * <p>任务组按哨兵定组：元数据与图都在且组内 key 完整时只读 Redis（热组）；
 * 否则整组以 MySQL 为准（冷组），并删除 Redis 残留。会话级 key 仍按缺失回落。
 * 旧 {@code lastTraceId} 非空或本轮为冷组时做中断规整。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Slf4j
@Component
public class TurnSessionHydrate {

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
     * 节点空间编解码（含溢出还原）。
     */
    private final SpaceCodec spaces;

    /**
     * 任务组 TTL 与续期。
     */
    private final GroupTtl groupTtl;

    /**
     * 状态窗口与 TTL 配置。
     */
    private final RuntimeProperties properties;

    /**
     * @param store         热态 Redis
     * @param conversations 会话仓储
     * @param tasks         任务仓储
     * @param graphs        图仓储
     * @param calls         工具调用仓储
     * @param spaces        节点空间编解码
     * @param groupTtl      任务组 TTL
     * @param properties    TTL 与窗口配置
     */
    public TurnSessionHydrate(StateStore store, ConversationRepository conversations, TaskRepository tasks,
                              GraphRepository graphs, CallRepository calls, SpaceCodec spaces, GroupTtl groupTtl,
                              RuntimeProperties properties) {
        this.store = store;
        this.conversations = conversations;
        this.tasks = tasks;
        this.graphs = graphs;
        this.calls = calls;
        this.spaces = spaces;
        this.groupTtl = groupTtl;
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
            log.error("failed to load", e);
            throw new BusinessException(RuntimeErrorCode.STATE_UNAVAILABLE);
        }
        ChatConversationVo row = conversations.find(conversationId);
        ConversationFence fence = StateCodec.fence(conversationId, hash);
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
        // 本轮新建会话时记下标题，开局下发一次 TITLE
        String createdTitle = Objects.isNull(row) ? conversations.create(conversationId, user, request.getUserInput()) : null;
        int dialogueMax = properties.getState().getDialogueMax();
        int attachmentMaxPerTurn = properties.getAttachment().getMaxPerTurn();
        int attachmentMaxChars = properties.getAttachment().getMaxChars();
        List<DialogueMessage> dialogue = StateCodec.dialogue(
            store.lRange(keys.dialogue(), 0, dialogueMax - 1L));
        if (Collections.isEmpty(dialogue)) {
            dialogue = conversations.loadDialogue(conversationId, dialogueMax);
        }
        List<TaskRecord> records = StateCodec.records(
            store.lRange(keys.records(), 0, properties.getState().getRecordsMax() - 1L));
        if (Collections.isEmpty(records)) {
            records = tasks.loadRecords(conversationId, properties.getState().getRecordsMax());
        }
        Map<String, String> entitiesHash = store.hGetAll(keys.entities());
        Map<String, EntityEntry> entities = StateCodec.entities(entitiesHash);
        Map<String, String> sessionSharedHash = store.hGetAll(keys.sessionShared());
        Map<String, Sourced<?>> sessionShared = StateCodec.shared(sessionSharedHash);
        if (Collections.isEmpty(sessionShared)) {
            sessionShared = new LinkedHashMap<>(conversations.loadSessionShared(conversationId));
        }
        TaskMetadata task = null;
        TaskGraph graph = new TaskGraph();
        TaskCheckpoint checkpoint = null;
        Map<String, Sourced<?>> shared = new LinkedHashMap<>();
        Prior prior = null;
        boolean cold = false;
        String coldTaskId = null;
        TaskGroup hotLoaded = null;
        String activeTaskId = fence.activeTaskId();
        if (Strings.isNotBlank(activeTaskId)) {
            TaskGroup group = hotGroup(keys, activeTaskId);
            if (Objects.isNull(group)) {
                group = coldGroup(keys, activeTaskId);
            }
            // 活动任务标识悬空（热/冷都取不到元数据，或元数据已是终态）：清掉标识，本轮按无活动任务处理
            if (Objects.isNull(group) || TaskStatus.isTerminal(group.metadata().getStatus())) {
                log.warn("active task dangling, cleared conversationId={} taskId={} status={}", conversationId,
                    activeTaskId, Objects.isNull(group) ? null : group.metadata().getStatus());
                fence = new ConversationFence(conversationId, fence.userId(), null, fence.version(), fence.lastTraceId(),
                    fence.updatedAt());
            } else {
                cold = group.cold();
                task = group.metadata();
                if (Strings.isBlank(task.getConversationId())) {
                    task.setConversationId(conversationId);
                }
                graph = group.graph();
                checkpoint = group.checkpoint();
                shared = group.shared();
                prior = group.prior();
                if (cold) {
                    coldTaskId = task.getTaskId();
                } else {
                    hotLoaded = group;
                }
            }
        }
        DefaultTurnScope turn = new DefaultTurnScope(organId, userId, traceId, fence, dialogue, records, entities, task,
            graph, checkpoint, shared, prior, sessionShared, dialogueMax, attachmentMaxPerTurn, attachmentMaxChars);
        turn.markCreated(createdTitle);
        seedSession(turn, keys, entitiesHash, sessionSharedHash);
        // 热组：记下加载时已校验的节点，轮中 key 缺失即视为淘汰；并用 hotGroup 已读数据登记
        if (Objects.nonNull(hotLoaded)) {
            List<String> verifiedIds = new ArrayList<>();
            for (TaskNode node : graph.getNodes()) {
                verifiedIds.add(node.getNodeId());
            }
            turn.markVerified(verifiedIds);
            seedHotGroup(turn, keys, hotLoaded.metadata().getTaskId(), hotLoaded);
        }
        // 冷组：图中全部节点立即从 MySQL 挂上，供本轮 begin flush 整组写回
        if (Strings.isNotBlank(coldTaskId)) {
            for (TaskNode node : graph.getNodes()) {
                String nodeId = node.getNodeId();
                attach(turn, nodeId, graphs.loadSpace(coldTaskId, nodeId), calls.loadNodeEvidence(coldTaskId, nodeId));
            }
        }
        List<KeyTtl> extra = groupTtl.renewals(keys, turn, groupTtl.of(task));
        RenewResult renewed;
        try {
            renewed = store.renew(keys.state(), traceId, properties.getState().getConversationTtl(), keys.sessionShared(),
                extra, fence.version());
        } catch (RuntimeException e) {
            log.error("failed to load", e);
            throw new BusinessException(RuntimeErrorCode.STATE_UNAVAILABLE);
        }
        turn.replaceFence(new ConversationFence(fence.conversationId(), fence.userId(), fence.activeTaskId(),
            renewed.version(), traceId, System.currentTimeMillis()));
        // 上一轮未收尾，或本轮冷组：RUNNING→READY，STARTED→UNKNOWN
        if (renewed.interrupted() || cold) {
            tidyInterrupt(turn, keys);
        }

        return turn;
    }

    /**
     * 热组判定：哨兵与图都在，且组内 key 全部存在；通过则只读 Redis。
     *
     * @param keys   会话 Redis key
     * @param taskId 活动任务 id
     * @return 热组；不完整为 {@code null}
     */
    private @Nullable TaskGroup hotGroup(SessionKeys keys, String taskId) {
        Map<String, String> metadataHash = store.hGetAll(keys.metadata(taskId));
        TaskMetadata metadata = StateCodec.metadata(metadataHash);
        if (Objects.isNull(metadata)) {
            return null;
        }
        if (Strings.isBlank(metadata.getTaskId())) {
            metadata.setTaskId(taskId);
        }
        Map<String, String> graphHash = store.hGetAll(keys.graph(taskId));
        TaskGraph graph = StateCodec.graph(graphHash);
        if (Objects.isNull(graph)) {
            return null;
        }
        List<String> groupKeys = keys.group(taskId, graph);
        if (store.countExisting(groupKeys) != groupKeys.size()) {
            return null;
        }
        String checkpointRaw = store.get(keys.checkpoint(taskId));
        Map<String, String> sharedHash = store.hGetAll(keys.shared(taskId));
        String priorRaw = store.get(keys.prior(taskId));
        TaskCheckpoint checkpoint = StateCodec.checkpoint(checkpointRaw);
        Map<String, Sourced<?>> shared = StateCodec.shared(sharedHash);
        Prior prior = StateCodec.prior(priorRaw);
        return new TaskGroup(metadata, graph, checkpoint, shared, prior, false, metadataHash, graphHash, sharedHash,
            checkpointRaw, priorRaw);
    }

    /**
     * 冷组：以 MySQL 为准，删除 Redis 残留后整组回灌；库中无元数据时返回 {@code null}。
     *
     * @param keys   会话 Redis key
     * @param taskId 活动任务 id
     * @return 冷组；MySQL 无元数据为 {@code null}
     */
    private @Nullable TaskGroup coldGroup(SessionKeys keys, String taskId) {
        TaskMetadata metadata = tasks.loadMetadata(taskId);
        if (Objects.isNull(metadata)) {
            return null;
        }
        if (Strings.isBlank(metadata.getTaskId())) {
            metadata.setTaskId(taskId);
        }
        TaskGraph redisGraph = StateCodec.graph(store.hGetAll(keys.graph(taskId)));
        TaskGraph mysqlGraph = graphs.load(taskId);
        if (Objects.isNull(mysqlGraph)) {
            mysqlGraph = new TaskGraph();
        }
        // 残留 key 按 Redis 图与 MySQL 图节点并集删除，避免旧字段混入
        store.del(keys.group(taskId, unionGraph(redisGraph, mysqlGraph)).toArray(String[]::new));
        TaskCheckpoint checkpoint = tasks.loadCheckpoint(taskId);
        Map<String, Sourced<?>> shared = new LinkedHashMap<>(tasks.loadShared(taskId));
        Prior prior = tasks.loadPrior(taskId);
        return new TaskGroup(metadata, mysqlGraph, checkpoint, shared, prior, true, Map.of(), Map.of(), Map.of(), null,
            null);
    }

    /**
     * Redis 图与 MySQL 图的节点并集（仅用于生成待删 key）。
     *
     * @param redis Redis 中的图；可空
     * @param mysql MySQL 中的图
     * @return 并集图
     */
    private static TaskGraph unionGraph(@Nullable TaskGraph redis, TaskGraph mysql) {
        TaskGraph union = new TaskGraph();
        Set<String> seen = new HashSet<>();
        List<TaskNode> nodes = new ArrayList<>();
        appendUnique(nodes, seen, redis);
        appendUnique(nodes, seen, mysql);
        union.setNodes(nodes);
        return union;
    }

    /**
     * 把来源图中尚未见过的节点追加到列表。
     *
     * @param nodes  目标列表
     * @param seen   已收录的 nodeId
     * @param source 来源图；可空
     */
    private static void appendUnique(List<TaskNode> nodes, Set<String> seen, @Nullable TaskGraph source) {
        if (Objects.isNull(source)) {
            return;
        }
        List<TaskNode> sourceNodes = source.getNodes();
        for (TaskNode node : sourceNodes) {
            if (seen.add(node.getNodeId())) {
                nodes.add(node);
            }
        }
    }

    /**
     * 中断规整：RUNNING 节点回 READY；图中全部节点的 STARTED 证据改 UNKNOWN（不论任务状态）。
     *
     * @param turn 本轮视图
     * @param keys Redis key
     */
    private void tidyInterrupt(DefaultTurnScope turn, SessionKeys keys) {
        turn.task().ifPresent(meta -> {
            boolean running = Objects.equals(meta.getStatus(), TaskStatus.RUNNING);
            turn.graph().getNodes().forEach(node -> {
                if (running && Objects.equals(node.getStatus(), NodeStatus.RUNNING)) {
                    turn.mark(node.getNodeId(), NodeStatus.READY, null, null);
                }
                DefaultNodeScope opened = openSpace(turn, keys, node.getNodeId());
                opened.evidence().stream()
                    .filter(head -> Objects.equals(head.status(), EvidenceStatus.STARTED))
                    .toList()
                    .forEach(head -> opened.addEvidence(head.withStatus(EvidenceStatus.UNKNOWN)));
            });
        });
    }

    /**
     * 打开节点空间（hydrate / Facade.open 共用）。热路径只读 Redis；
     * 已校验节点 key 缺失抛 {@code STATE_UNAVAILABLE}，本轮新建节点返回空视图。
     *
     * @param turn   本轮
     * @param keys   key
     * @param nodeId 节点 id
     * @return 节点视图
     */
    public DefaultNodeScope openSpace(DefaultTurnScope turn, SessionKeys keys, String nodeId) {
        return turn.node(nodeId).orElseGet(() -> {
            String taskId = turn.task().map(TaskMetadata::getTaskId).orElse(null);
            if (Strings.isBlank(taskId)) {
                return attach(turn, nodeId, null, List.of());
            }
            Map<String, String> spaceHash = store.hGetAll(keys.space(taskId, nodeId));
            Map<String, String> evidenceHash = store.hGetAll(keys.evidence(taskId, nodeId));
            if (Collections.isEmpty(spaceHash) || Collections.isEmpty(evidenceHash)) {
                // 已校验节点：轮中淘汰；否则为本轮新建节点，返回空视图
                if (turn.verified(nodeId)) {
                    throw new BusinessException(RuntimeErrorCode.STATE_UNAVAILABLE);
                }
                return attach(turn, nodeId, null, List.of());
            }
            NodeSpace space = spaces.decode(spaceHash);
            DefaultNodeScope node = attach(turn, nodeId, space, StateCodec.evidence(evidenceHash));
            // 热组按需打开：按溢出还原后的口径登记，与 flush 对比溢出前内容一致
            turn.ledger().seedHash(keys.space(taskId, nodeId), spaces.expanded(spaceHash));
            turn.ledger().seedHash(keys.evidence(taskId, nodeId), evidenceHash);
            return node;
        });
    }

    /**
     * 登记会话级已确认内容：实体与会话共享；栅栏不登记（每次 flush 全量写）。
     *
     * @param turn              本轮
     * @param keys              key
     * @param entitiesHash      读到的实体 Hash
     * @param sessionSharedHash 读到的会话共享 Hash
     */
    private static void seedSession(DefaultTurnScope turn, SessionKeys keys, Map<String, String> entitiesHash,
                                    Map<String, String> sessionSharedHash) {
        turn.ledger().seedHash(keys.entities(), entitiesHash);
        turn.ledger().seedHash(keys.sessionShared(), sessionSharedHash);
    }

    /**
     * 登记热组已确认内容：元数据、图、任务共享、断点、前情；写索引与节点只在打开时登记。
     *
     * @param turn   本轮
     * @param keys   key
     * @param taskId 任务 id
     * @param group  hotGroup 已读到的原始数据
     */
    private static void seedHotGroup(DefaultTurnScope turn, SessionKeys keys, String taskId, TaskGroup group) {
        FlushLedger ledger = turn.ledger();
        ledger.seedHash(keys.metadata(taskId), group.metadataHash());
        ledger.seedHash(keys.graph(taskId), group.graphHash());
        ledger.seedHash(keys.shared(taskId), group.sharedHash());
        ledger.seedString(keys.checkpoint(taskId), group.checkpointRaw());
        ledger.seedString(keys.prior(taskId), group.priorRaw());
    }

    /**
     * 构造节点视图并挂到本轮；证据按轮次排序。
     *
     * @param turn     本轮视图
     * @param nodeId   节点 id
     * @param space    节点空间；可空则空空间
     * @param evidence 证据列表
     * @return 已挂上的节点视图
     */
    private DefaultNodeScope attach(DefaultTurnScope turn, String nodeId, @Nullable NodeSpace space,
                                    List<EvidenceHead> evidence) {
        if (Objects.isNull(space)) {
            space = new NodeSpace(Map.of(), Map.of(), Map.of(), 0, 0, List.of());
        }
        List<EvidenceHead> ordered = new ArrayList<>(evidence);
        ordered.sort(Comparator.comparingInt(EvidenceHead::round));
        DefaultNodeScope node = new DefaultNodeScope(turn, nodeId, space.inputs(), space.outputs(), space.state(),
            ordered, space.actions(), space.rounds(), space.tokens());
        turn.attach(node);
        return node;
    }

    /**
     * 任务组装载结果：热组只含 Redis 数据，冷组只含 MySQL 数据。
     *
     * @param metadata      任务元数据
     * @param graph         任务图
     * @param checkpoint    断点；可空
     * @param shared        任务共享
     * @param prior         前情；可空
     * @param cold          是否冷组
     * @param metadataHash  热组读到的元数据 Hash；冷组为空
     * @param graphHash     热组读到的图 Hash；冷组为空
     * @param sharedHash    热组读到的任务共享 Hash；冷组为空
     * @param checkpointRaw 热组读到的断点原文；冷组为 null
     * @param priorRaw      热组读到的前情原文；冷组为 null
     * @author alpha
     * @since 2026/11/22
     */
    private record TaskGroup(TaskMetadata metadata, TaskGraph graph, @Nullable TaskCheckpoint checkpoint,
                             Map<String, Sourced<?>> shared, @Nullable Prior prior, boolean cold,
                             Map<String, String> metadataHash, Map<String, String> graphHash,
                             Map<String, String> sharedHash, @Nullable String checkpointRaw,
                             @Nullable String priorRaw) {

    }
}

