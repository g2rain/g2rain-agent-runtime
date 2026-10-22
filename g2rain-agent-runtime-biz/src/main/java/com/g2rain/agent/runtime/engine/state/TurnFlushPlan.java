package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.NodeSpace;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.engine.store.FlushOp;
import com.g2rain.agent.runtime.engine.store.StateStore;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把本轮内存视图编成 flush.lua 改动列表并提交 Redis。
 *
 * <p>栅栏不符时抛业务异常。expire 模式只在 persist 成功后由 {@link #apply(DefaultTurnScope, boolean)}
 * 的 {@code clearLastTrace=true} 路径触发。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Slf4j
@Component
public class TurnFlushPlan {

    /**
     * 领域对象与 flush 操作的 JSON 编解码器。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 热态 Redis。
     */
    private final StateStore store;

    /**
     * flush 体积上限与 TTL。
     */
    private final RuntimeProperties properties;

    /**
     * 任务组续期 key 与 NodeSpace 编解码。
     */
    private final TurnSessionHydrate hydrate;

    /**
     * @param store      热态
     * @param properties 配置
     * @param hydrate    回灌与空间编解码
     */
    public TurnFlushPlan(StateStore store, RuntimeProperties properties, TurnSessionHydrate hydrate) {
        this.store = store;
        this.properties = properties;
        this.hydrate = hydrate;
    }

    /**
     * 按本轮栅栏写入 Redis。
     *
     * @param turn           本轮视图
     * @param clearLastTrace 是否清空 lastTraceId（仅轮末成功 persist 后）
     */
    public void apply(DefaultTurnScope turn, boolean clearLastTrace) {
        SessionKeys keys = SessionKeys.of(turn);
        if (clearLastTrace) {
            turn.clearLastTrace();
        }
        List<FlushOp> ops = ops(turn, keys);
        byte[] bytes = JSON.obj2byte(ops);
        if (bytes.length > properties.getState().getFlushMaxBytes()) {
            throw new BusinessException(RuntimeErrorCode.STATE_WRITE_FAILED);
        }
        boolean ok;
        try {
            ok = store.flush(keys.state(), turn.conversation().version(), ops);
        } catch (RuntimeException e) {
            log.error("failed to apply", e);
            throw new BusinessException(RuntimeErrorCode.STATE_UNAVAILABLE);
        }
        if (!ok) {
            // Lua 返回 0：本轮 version 已不是 Redis 栅栏，内存改动不得当权威
            throw new BusinessException(RuntimeErrorCode.STATE_FENCED);
        }
        turn.markDialogueFlushed();
        turn.markPayloadsFlushed();
        StateSizeProbe.record(ops);
    }

    /**
     * 把本轮栅栏、待刷交互历史、实体、会话共享与任务组编成 Lua 改动列表。
     *
     * @param turn 本轮视图
     * @param keys 会话 Redis key
     * @return 本轮 flush 操作
     */
    public List<FlushOp> ops(DefaultTurnScope turn, SessionKeys keys) {
        List<FlushOp> ops = new ArrayList<>();
        writeFence(ops, keys, turn.conversation());
        long dialogueMax = properties.getState().getDialogueMax();
        turn.pendingDialogue().forEach(message ->
            ops.add(FlushOp.lpushTrim(keys.dialogue(), JSON.obj2str(message), dialogueMax)));
        turn.entities().forEach((field, entry) ->
            ops.add(FlushOp.hset(keys.entities(), field, JSON.obj2str(entry), null)));
        turn.sessionShared().forEach((field, value) ->
            ops.add(FlushOp.hset(keys.sessionShared(), field, JSON.obj2str(value), null)));
        turn.task().ifPresent(task -> writeTask(ops, keys, turn, task));
        return ops;
    }

    /**
     * 任务组 Hash / 节点 space / 证据。
     *
     * @param ops  改动
     * @param keys key
     * @param turn 本轮
     * @param task 任务
     */
    private void writeTask(List<FlushOp> ops, SessionKeys keys, DefaultTurnScope turn, TaskMetadata task) {
        String taskId = task.getTaskId();
        Duration ttl = hydrate.taskTtl(task);
        long ttlMs = ttl.toMillis();
        Set<String> protectedPayloadNodes = upstreamProtectedNodes(turn);
        // 任务级：元数据、图、断点（无则删）、任务共享、前情，并续期写索引
        writeHash(ops, keys.metadata(taskId), task, ttlMs, "conversationId", "taskId", "version");
        hsetAll(ops, keys.graph(taskId), HashCodec.graphHash(turn.graph()), ttlMs);
        if (turn.checkpoint().isPresent()) {
            ops.add(FlushOp.set(keys.checkpoint(taskId), JSON.obj2str(turn.checkpoint().orElseThrow()), ttlMs));
        } else {
            ops.add(FlushOp.del(keys.checkpoint(taskId)));
        }
        turn.shared().forEach((field, value) ->
            ops.add(FlushOp.hset(keys.shared(taskId), field, JSON.obj2str(value), ttlMs)));
        turn.prior().ifPresent(prior -> ops.add(FlushOp.set(keys.prior(taskId), JSON.obj2str(prior), ttlMs)));
        ops.add(FlushOp.expire(keys.writes(taskId), ttlMs));
        // 节点级：空间字段、证据头与写索引；已完成节点在下游不再需要时收缩载荷
        turn.nodes().values().forEach(node -> {
            NodeSpace space = new NodeSpace(node.inputs(), node.outputs(), node.state(),
                node.rounds(), node.tokens(), node.actions());
            String spaceKey = keys.space(taskId, node.nodeId());
            hsetAll(ops, spaceKey, hydrate.encodeSpace(taskId, node.nodeId(), space), ttlMs);
            HashCodec.deletedFields(space).forEach(field -> ops.add(FlushOp.hdel(spaceKey, field)));
            node.evidence().forEach(head -> {
                ops.add(FlushOp.hset(keys.evidence(taskId, node.nodeId()), head.evidenceId(), JSON.obj2str(head), ttlMs));
                writeIndex(ops, keys, taskId, head, ttlMs);
            });
            shrinkDonePayloads(ops, keys, turn, taskId, node, protectedPayloadNodes, ttlMs);
        });
        // 本轮新产生的证据载荷
        turn.payloads().forEach((id, payload) -> writePayload(ops, keys, turn, taskId, id, payload,
            protectedPayloadNodes, ttlMs));
    }

    /**
     * 写工具证据同步更新 {@code task:writes}（与 EvidenceHead 同一次 flush）。
     *
     * @param ops    本轮 flush 改动
     * @param keys   会话 Redis key
     * @param taskId 任务 id
     * @param head   证据头
     * @param ttlMs  任务组 TTL 毫秒
     */
    private void writeIndex(List<FlushOp> ops, SessionKeys keys, String taskId, EvidenceHead head, long ttlMs) {
        if (!Objects.equals(head.sideEffect(), SideEffect.WRITE) || Strings.isBlank(head.idempotencyKey())) {
            return;
        }
        WriteIndexEntry entry = WriteIndexEntry.of(head);
        if (Objects.isNull(entry)) {
            return;
        }
        ops.add(FlushOp.hset(keys.writes(taskId), WriteIndexEntry.field(head.toolName(), head.idempotencyKey()),
            JSON.obj2str(entry), ttlMs));
    }

    /**
     * 本轮新写入的报文：DONE 且无委派保护时用短 TTL / 删除。
     *
     * @param ops            本轮 flush 改动
     * @param keys           会话 Redis key
     * @param turn           本轮视图
     * @param taskId         任务 id
     * @param evidenceId     证据 id，同时是报文键后缀
     * @param payload        报文体；可空则写成 JSON null
     * @param protectedNodes 下游未结束的上游节点
     * @param taskTtlMs      任务组 TTL 毫秒
     */
    private void writePayload(List<FlushOp> ops, SessionKeys keys, DefaultTurnScope turn, String taskId,
                              String evidenceId, @Nullable JsonNode payload, Set<String> protectedNodes, long taskTtlMs) {
        String body = JSON.obj2str(payload);
        String owner = ownerNodeId(turn, evidenceId);
        Long ttlMs = payloadTtlMs(turn, owner, protectedNodes, taskTtlMs);
        if (Objects.isNull(ttlMs)) {
            ops.add(FlushOp.del(keys.payload(taskId, evidenceId)));
            return;
        }
        ops.add(FlushOp.set(keys.payload(taskId, evidenceId), body, ttlMs));
    }

    /**
     * 节点已 DONE：对本节点已有证据报文缩 TTL（即使本轮 payloads 未再 put）。
     *
     * @param ops            本轮 flush 改动
     * @param keys           会话 Redis key
     * @param turn           本轮视图
     * @param taskId         任务 id
     * @param node           已打开的节点视图
     * @param protectedNodes 下游未结束的上游节点
     * @param taskTtlMs      任务组 TTL 毫秒
     */
    private void shrinkDonePayloads(List<FlushOp> ops, SessionKeys keys, DefaultTurnScope turn, String taskId,
                                    DefaultNodeScope node, Set<String> protectedNodes, long taskTtlMs) {
        Long ttlMs = payloadTtlMs(turn, node.nodeId(), protectedNodes, taskTtlMs);
        if (Objects.isNull(ttlMs) || Objects.equals(ttlMs, taskTtlMs)) {
            if (Objects.isNull(ttlMs)) {
                node.evidence().forEach(head -> {
                    if (!turn.payloads().containsKey(head.evidenceId())) {
                        ops.add(FlushOp.del(keys.payload(taskId, head.evidenceId())));
                    }
                });
            }
            return;
        }
        node.evidence().forEach(head -> {
            if (!turn.payloads().containsKey(head.evidenceId())) {
                ops.add(FlushOp.expire(keys.payload(taskId, head.evidenceId()), ttlMs));
            }
        });
    }

    /**
     * 计算证据报文 TTL。
     *
     * @param turn           本轮视图
     * @param nodeId         报文所属节点；可空则按任务组 TTL
     * @param protectedNodes 下游未结束的上游节点
     * @param taskTtlMs      任务组 TTL 毫秒
     * @return 任务组 TTL、短 TTL；{@code null} 表示删除 Redis 副本
     */
    private @Nullable Long payloadTtlMs(DefaultTurnScope turn, @Nullable String nodeId, Set<String> protectedNodes,
                                        long taskTtlMs) {
        if (Strings.isBlank(nodeId) || protectedNodes.contains(nodeId) || !isDone(turn, nodeId)) {
            return taskTtlMs;
        }
        Duration doneTtl = properties.getState().getPayloadDoneTtl();
        if (doneTtl.isZero() || doneTtl.isNegative()) {
            return null;
        }
        return doneTtl.toMillis();
    }

    /**
     * 图节点是否已 DONE。
     *
     * @param turn   本轮视图
     * @param nodeId 节点 id
     * @return 节点存在且状态为 DONE
     */
    private static boolean isDone(DefaultTurnScope turn, String nodeId) {
        TaskNode graphNode = turn.graph().find(nodeId);
        return Objects.nonNull(graphNode) && Objects.equals(graphNode.getStatus(), NodeStatus.DONE);
    }

    /**
     * 下游节点未结束时，保护其上游节点的报文热态（下游会直接读上游证据）。
     *
     * @param turn 本轮视图
     * @return 被保护的上游节点 id；没有时为空集合
     */
    private static Set<String> upstreamProtectedNodes(DefaultTurnScope turn) {
        Set<String> protectedIds = new HashSet<>();
        for (TaskNode node : turn.graph().getNodes()) {
            if (!node.getStatus().isTerminal()) {
                protectedIds.addAll(turn.graph().upstream(node.getNodeId()));
            }
        }
        return protectedIds;
    }

    /**
     * 按证据 id 找回所属节点。
     *
     * @param turn       本轮视图
     * @param evidenceId 证据 id
     * @return 节点 id；未挂到任何已打开节点时为 {@code null}
     */
    private static @Nullable String ownerNodeId(DefaultTurnScope turn, String evidenceId) {
        for (DefaultNodeScope node : turn.nodes().values()) {
            for (EvidenceHead head : node.evidence()) {
                if (Objects.equals(head.evidenceId(), evidenceId)) {
                    return node.nodeId();
                }
            }
        }
        return null;
    }

    /**
     * persist 成功且任务终态：追加 record、删任务组。须在 {@link DefaultTurnScope#clearActiveTask()} 之前调用。
     *
     * @param turn   本轮
     * @param record 结束记录
     */
    public void dropClosedTask(DefaultTurnScope turn, TaskRecord record) {
        turn.task().ifPresent(task -> dropClosedTask(turn, task, turn.graph(), turn.allPayloadIds(), record));
    }

    /**
     * 按指定任务 id / 图删 Redis 任务组，并追加 record。
     *
     * @param turn       本轮（取会话 key 与栅栏）
     * @param task       已结束任务
     * @param graph      关闭时的图
     * @param payloadIds 待删报文 id
     * @param record     结束记录
     */
    public void dropClosedTask(DefaultTurnScope turn, TaskMetadata task, TaskGraph graph, Collection<String> payloadIds,
                               TaskRecord record) {
        SessionKeys keys = SessionKeys.of(turn);
        String taskId = task.getTaskId();
        List<FlushOp> ops = new ArrayList<>();
        ops.add(FlushOp.lpushTrim(keys.records(), JSON.obj2str(record), properties.getState().getRecordsMax()));
        ops.add(FlushOp.del(keys.metadata(taskId)));
        ops.add(FlushOp.del(keys.graph(taskId)));
        ops.add(FlushOp.del(keys.checkpoint(taskId)));
        ops.add(FlushOp.del(keys.shared(taskId)));
        ops.add(FlushOp.del(keys.prior(taskId)));
        ops.add(FlushOp.del(keys.writes(taskId)));
        graph.getNodes().forEach(node -> {
            ops.add(FlushOp.del(keys.space(taskId, node.getNodeId())));
            ops.add(FlushOp.del(keys.evidence(taskId, node.getNodeId())));
        });
        payloadIds.forEach(id -> ops.add(FlushOp.del(keys.payload(taskId, id))));
        if (!store.flush(keys.state(), turn.conversation().version(), ops)) {
            throw new BusinessException(RuntimeErrorCode.STATE_FENCED);
        }
    }

    /**
     * 按 {@link ConversationFence} 属性写 Hash。{@code conversationId} 在 key 上；{@code version} 由 renew.lua 维护。
     *
     * @param ops   改动
     * @param keys  会话 key
     * @param fence 栅栏
     */
    private void writeFence(List<FlushOp> ops, SessionKeys keys, ConversationFence fence) {
        // version 由 renew.lua 维护，flush 不改；null 字段 HDEL（含 lastTraceId / activeTaskId）
        writeHash(ops, keys.state(), fence, null, "version");
    }

    /**
     * 对象属性：非空 HSET，null HDEL。
     *
     * @param ops   本轮 flush 改动
     * @param key   Redis Hash key
     * @param obj   领域对象
     * @param ttlMs 可选 PEXPIRE；可空则不续期
     * @param skip  不写入的属性名
     */
    private void writeHash(List<FlushOp> ops, String key, Object obj, @Nullable Long ttlMs, String... skip) {
        hsetAll(ops, key, HashCodec.toHash(obj, skip), ttlMs);
        HashCodec.deletedFields(obj, skip).forEach(field -> ops.add(FlushOp.hdel(key, field)));
    }

    /**
     * 按对象属性写入 Hash 各 field。
     *
     * @param ops   改动
     * @param key   Redis key
     * @param hash  field → 值
     * @param ttlMs 可选 PEXPIRE
     */
    private void hsetAll(List<FlushOp> ops, String key, Map<String, String> hash, @Nullable Long ttlMs) {
        hash.forEach((field, value) -> ops.add(FlushOp.hset(key, field, value, ttlMs)));
    }

    /**
     * UTF-8 截断对话正文。
     *
     * @param text 原文；空则返回空串
     * @return 截断后的正文
     */
    public String clipMessage(@Nullable String text) {
        if (Strings.isBlank(text)) {
            return "";
        }
        int max = properties.getState().getMessageMaxBytes();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= max) {
            return text;
        }
        return new String(bytes, 0, max, StandardCharsets.UTF_8);
    }
}
