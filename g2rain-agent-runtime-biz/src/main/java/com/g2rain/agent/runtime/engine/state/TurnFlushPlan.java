package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.task.ClosedTask;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.NodeSpace;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.store.FlushOp;
import com.g2rain.agent.runtime.engine.store.KeyTtl;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把本轮内存视图编成 flush.lua 改动列表并提交 Redis。
 *
 * <p>轮中用 {@link #apply}；轮末 persist 成功后用 {@link #finish} 一次提交关闭任务组与清空
 * {@code lastTraceId}。对比结构只写与本轮已确认内容不同的字段；会话栅栏每次全量写；
 * 栅栏不符时抛业务异常。</p>
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
     * 节点空间编解码（含溢出）。
     */
    private final SpaceCodec spaces;

    /**
     * 任务组 TTL 与续期。
     */
    private final GroupTtl groupTtl;

    /**
     * @param store      热态
     * @param properties 配置
     * @param spaces     节点空间编解码
     * @param groupTtl   任务组 TTL
     */
    public TurnFlushPlan(StateStore store, RuntimeProperties properties, SpaceCodec spaces, GroupTtl groupTtl) {
        this.store = store;
        this.properties = properties;
        this.spaces = spaces;
        this.groupTtl = groupTtl;
    }

    /**
     * 按本轮栅栏写入 Redis（轮中 flush，不清 {@code lastTraceId}）。
     *
     * @param turn 本轮视图
     */
    public void apply(DefaultTurnScope turn) {
        SessionKeys keys = SessionKeys.of(turn);
        LedgerDelta delta = new LedgerDelta();
        submit(turn, keys, ops(turn, keys, delta), delta);
    }

    /**
     * 轮末收尾：已结束任务追加记录并删除任务组、清空活动任务与 lastTraceId，与本轮其余改动一次提交。
     *
     * @param turn    本轮视图（persist 已成功）
     * @param records persist 写入的任务记录，taskId → 记录
     */
    public void finish(DefaultTurnScope turn, Map<String, TaskRecord> records) {
        SessionKeys keys = SessionKeys.of(turn);
        LedgerDelta delta = new LedgerDelta();
        List<FlushOp> closeOps = new ArrayList<>();
        for (ClosedTask closed : turn.closedTasks()) {
            appendCloseOps(closeOps, keys, closed.metadata().getTaskId(), closed.graph(), closed.payloadIds(),
                records, delta);
        }
        boolean terminal = turn.task().map(task -> TaskStatus.isTerminal(task.getStatus())).orElse(false);
        if (terminal) {
            TaskMetadata task = turn.task().orElseThrow();
            appendCloseOps(closeOps, keys, task.getTaskId(), turn.graph(), turn.allPayloadIds(), records, delta);
            turn.clearActiveTask();
        }
        turn.clearLastTrace();
        List<FlushOp> state = new ArrayList<>(closeOps);
        state.addAll(ops(turn, keys, delta));
        submit(turn, keys, state, delta);
    }

    /**
     * 校验状态改动体积后，拼上报文 ops，一次提交 Redis；成功后 commit ledger。
     *
     * @param turn  本轮视图
     * @param keys  会话 Redis key
     * @param state 关闭 ops（可空）+ 状态改动；不含报文
     * @param delta 本轮对比确认内容
     */
    private void submit(DefaultTurnScope turn, SessionKeys keys, List<FlushOp> state, LedgerDelta delta) {
        byte[] bytes = JSON.obj2byte(state);
        if (bytes.length > properties.getState().getFlushMaxBytes()) {
            throw new BusinessException(RuntimeErrorCode.STATE_WRITE_FAILED);
        }
        List<FlushOp> all = new ArrayList<>(state);
        all.addAll(payloadOps(turn, keys));
        boolean ok;
        try {
            ok = store.flush(keys.state(), turn.conversation().version(), all);
        } catch (RuntimeException e) {
            log.error("failed to submit", e);
            throw new BusinessException(RuntimeErrorCode.STATE_UNAVAILABLE);
        }
        if (!ok) {
            // Lua 返回 0：本轮 version 已不是 Redis 栅栏，内存改动不得当权威；不 commit
            throw new BusinessException(RuntimeErrorCode.STATE_FENCED);
        }
        turn.ledger().commit(delta);
        turn.markDialogueFlushed();
        turn.markPayloadsFlushed();
        StateSizeProbe.record(all);
    }

    /**
     * 已结束任务：追加记录、删任务组与报文。
     *
     * @param ops        关闭改动
     * @param keys       会话 Redis key
     * @param taskId     任务 id
     * @param graph      关闭时的图
     * @param payloadIds 待删报文 id
     * @param records    persist 写入的任务记录
     * @param delta      ledger 删除登记
     */
    private void appendCloseOps(List<FlushOp> ops, SessionKeys keys, String taskId, TaskGraph graph,
                                List<String> payloadIds, Map<String, TaskRecord> records, LedgerDelta delta) {
        TaskRecord record = records.get(taskId);
        if (Objects.isNull(record)) {
            throw new BusinessException(RuntimeErrorCode.STATE_WRITE_FAILED);
        }
        ops.add(FlushOp.lpushTrim(keys.records(), JSON.obj2str(record), properties.getState().getRecordsMax()));
        for (String groupKey : keys.group(taskId, graph)) {
            ops.add(FlushOp.del(groupKey));
            delta.remove(groupKey);
        }
        for (String payloadId : payloadIds) {
            ops.add(FlushOp.del(keys.payload(taskId, payloadId)));
        }
    }

    /**
     * 把本轮栅栏、待刷交互历史、实体、会话共享与任务组编成 Lua 改动列表（按变更）。
     *
     * @param turn 本轮视图
     * @param keys 会话 Redis key
     * @return 本轮 flush 操作
     */
    public List<FlushOp> ops(DefaultTurnScope turn, SessionKeys keys) {
        return ops(turn, keys, new LedgerDelta());
    }

    /**
     * 组装状态改动，并把对比终态记入 {@code delta}（不 commit）。
     *
     * @param turn  本轮视图
     * @param keys  会话 Redis key
     * @param delta 本轮确认内容
     * @return 本轮 flush 操作
     */
    private List<FlushOp> ops(DefaultTurnScope turn, SessionKeys keys, LedgerDelta delta) {
        List<FlushOp> ops = new ArrayList<>();
        FlushLedger ledger = turn.ledger();
        // 栅栏：renew.lua 也会写 state，不走对比，每次全量 HSET + null 字段 HDEL
        writeFence(ops, keys, turn.conversation());
        long dialogueMax = properties.getState().getDialogueMax();
        turn.pendingDialogue().forEach(message ->
            ops.add(FlushOp.lpushTrim(keys.dialogue(), StateCodec.message(message), dialogueMax)));
        applyHash(ops, delta, ledger, keys.entities(), StateCodec.entitiesHash(turn.entities()), null);
        applyHash(ops, delta, ledger, keys.sessionShared(), StateCodec.sharedHash(turn.sessionShared(), false), null);
        turn.task().ifPresent(task -> writeTask(ops, keys, turn, task, delta));
        // 会话级 key 随会话过期；每轮写后设置，只有一轮的会话也不会常驻
        long conversationTtlMs = properties.getState().getConversationTtl().toMillis();
        ops.add(FlushOp.expire(keys.dialogue(), conversationTtlMs));
        ops.add(FlushOp.expire(keys.records(), conversationTtlMs));
        ops.add(FlushOp.expire(keys.entities(), conversationTtlMs));
        ops.add(FlushOp.expire(keys.sessionShared(), conversationTtlMs));
        return ops;
    }

    /**
     * 会话栅栏每次全量写：非空字段 HSET，null 字段 HDEL；不登记 ledger。
     *
     * @param ops   改动
     * @param keys  会话 key
     * @param fence 栅栏
     */
    private static void writeFence(List<FlushOp> ops, SessionKeys keys, ConversationFence fence) {
        String key = keys.state();
        StateCodec.fenceHash(fence).forEach((field, value) -> ops.add(FlushOp.hset(key, field, value, null)));
        for (String field : StateCodec.fenceCleared(fence)) {
            ops.add(FlushOp.hdel(key, field));
        }
    }

    /**
     * 任务组 Hash / 节点 space / 证据（按变更）。
     *
     * @param ops   改动
     * @param keys  key
     * @param turn  本轮
     * @param task  任务
     * @param delta 确认内容
     */
    private void writeTask(List<FlushOp> ops, SessionKeys keys, DefaultTurnScope turn, TaskMetadata task,
                           LedgerDelta delta) {
        String taskId = task.getTaskId();
        Duration ttl = groupTtl.of(task);
        long ttlMs = ttl.toMillis();
        FlushLedger ledger = turn.ledger();
        Set<String> protectedPayloadNodes = upstreamProtectedNodes(turn);
        applyHash(ops, delta, ledger, keys.metadata(taskId), StateCodec.metadataHash(task), ttlMs);
        applyHash(ops, delta, ledger, keys.graph(taskId), StateCodec.graphHash(turn.graph()), ttlMs);
        applyString(ops, delta, ledger, keys.checkpoint(taskId),
            StateCodec.checkpointValue(turn.checkpoint().orElse(null)), ttlMs);
        applyHash(ops, delta, ledger, keys.shared(taskId), StateCodec.sharedHash(turn.shared(), true), ttlMs);
        applyString(ops, delta, ledger, keys.prior(taskId),
            StateCodec.priorValue(turn.prior().orElse(null)), ttlMs);
        List<EvidenceHead> openedEvidence = new ArrayList<>();
        Set<String> opened = turn.nodes().keySet();
        // 节点级：先比溢出前内容，只对变化字段做溢出编码
        turn.nodes().values().forEach(node -> {
            NodeSpace space = new NodeSpace(node.inputs(), node.outputs(), node.state(),
                node.rounds(), node.tokens(), node.actions());
            String spaceKey = keys.space(taskId, node.nodeId());
            Map<String, String> plain = new LinkedHashMap<>();
            plain.put(HashCodec.MARK, "");
            plain.putAll(HashCodec.toHash(space));
            applySpace(ops, delta, ledger, spaces, taskId, node.nodeId(), spaceKey, plain, ttlMs);
            applyHash(ops, delta, ledger, keys.evidence(taskId, node.nodeId()),
                StateCodec.evidenceHash(node.evidence()), ttlMs);
            openedEvidence.addAll(node.evidence());
            shrinkDonePayloads(ops, keys, turn, taskId, node, protectedPayloadNodes, ttlMs);
        });
        // 写索引：内存只有打开节点上的证据，只增不删
        applyHashPutOnly(ops, delta, ledger, keys.writes(taskId), StateCodec.writesHash(openedEvidence), ttlMs,
            turn);
        // 未打开节点：已校验的不写；新建的只在首次写 MARK，从不 HDEL
        for (TaskNode graphNode : turn.graph().getNodes()) {
            String nodeId = graphNode.getNodeId();
            if (opened.contains(nodeId) || turn.verified(nodeId)) {
                continue;
            }
            ensureMark(ops, delta, ledger, keys.space(taskId, nodeId), ttlMs);
            ensureMark(ops, delta, ledger, keys.evidence(taskId, nodeId), ttlMs);
        }
        // 整组统一续期：每次照发
        List<KeyTtl> renewals = groupTtl.renewals(keys, turn, ttl);
        for (KeyTtl renewal : renewals) {
            ops.add(FlushOp.expire(renewal.key(), renewal.ttlMs()));
        }
    }

    /**
     * Hash 只增不删：只 HSET 与已确认内容不同的字段；commit 时与已确认内容合并。
     *
     * <p>热组未登记写索引且本轮没有新条目时，跳过仅 {@link HashCodec#MARK} 的空写。</p>
     *
     * @param ops     改动
     * @param delta   确认内容
     * @param ledger  账本
     * @param key     Redis key
     * @param current 本次编码（可为全集的子集）
     * @param ttlMs   可选 TTL
     * @param turn    本轮（判断是否热组已校验）
     */
    private static void applyHashPutOnly(List<FlushOp> ops, LedgerDelta delta, FlushLedger ledger, String key,
                                         Map<String, String> current, @Nullable Long ttlMs, DefaultTurnScope turn) {
        Map<String, String> put = ledger.putDiff(key, current);
        if (put.isEmpty()) {
            return;
        }
        if (!ledger.hasHash(key) && put.size() == 1 && put.containsKey(HashCodec.MARK) && hasVerified(turn)) {
            return;
        }
        for (Map.Entry<String, String> entry : put.entrySet()) {
            ops.add(FlushOp.hset(key, entry.getKey(), entry.getValue(), ttlMs));
        }
        Map<String, String> merged = new LinkedHashMap<>(ledger.fields(key));
        merged.putAll(put);
        delta.confirmHash(key, merged);
    }

    /**
     * 未登记的 key 写一次 {@link HashCodec#MARK} 并登记；已登记则不动。
     *
     * @param ops    改动
     * @param delta  确认内容
     * @param ledger 账本
     * @param key    Redis key
     * @param ttlMs  TTL
     */
    private static void ensureMark(List<FlushOp> ops, LedgerDelta delta, FlushLedger ledger, String key, long ttlMs) {
        if (ledger.hasHash(key)) {
            return;
        }
        Map<String, String> mark = Map.of(HashCodec.MARK, "");
        ops.add(FlushOp.hset(key, HashCodec.MARK, "", ttlMs));
        delta.confirmHash(key, mark);
    }

    /**
     * @param turn 本轮
     * @return 图中是否存在加载时已校验的节点
     */
    private static boolean hasVerified(DefaultTurnScope turn) {
        for (TaskNode node : turn.graph().getNodes()) {
            if (turn.verified(node.getNodeId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hash 对比后只发差异；并把终态记入 delta。
     *
     * @param ops     改动
     * @param delta   确认内容
     * @param ledger  账本
     * @param key     Redis key
     * @param current 本次编码
     * @param ttlMs   可选 TTL
     */
    private static void applyHash(List<FlushOp> ops, LedgerDelta delta, FlushLedger ledger, String key,
                                  Map<String, String> current, @Nullable Long ttlMs) {
        HashDiff diff = ledger.diff(key, current);
        for (Map.Entry<String, String> entry : diff.put().entrySet()) {
            ops.add(FlushOp.hset(key, entry.getKey(), entry.getValue(), ttlMs));
        }
        for (String field : diff.delete()) {
            ops.add(FlushOp.hdel(key, field));
        }
        delta.confirmHash(key, current);
    }

    /**
     * 节点空间：对比溢出前内容，只对变化字段做溢出编码。
     *
     * @param ops     改动
     * @param delta   确认内容
     * @param ledger  账本
     * @param spaces  空间编解码
     * @param taskId  任务 id
     * @param nodeId  节点 id
     * @param key     空间 Redis key
     * @param plain   溢出前的字段表（含 MARK）
     * @param ttlMs   TTL
     */
    private static void applySpace(List<FlushOp> ops, LedgerDelta delta, FlushLedger ledger, SpaceCodec spaces,
                                   String taskId, String nodeId, String key, Map<String, String> plain,
                                   long ttlMs) {
        HashDiff diff = ledger.diff(key, plain);
        for (Map.Entry<String, String> entry : diff.put().entrySet()) {
            String field = entry.getKey();
            String value = Objects.equals(field, HashCodec.MARK)
                ? entry.getValue()
                : spaces.encodeField(taskId, nodeId, field, entry.getValue());
            ops.add(FlushOp.hset(key, field, value, ttlMs));
        }
        for (String field : diff.delete()) {
            ops.add(FlushOp.hdel(key, field));
        }
        // 账本按溢出前口径确认，下次对比仍不触发重复落库
        delta.confirmHash(key, plain);
    }

    /**
     * String 对比后只在变化时 SET；并把终态记入 delta。
     *
     * @param ops     改动
     * @param delta   确认内容
     * @param ledger  账本
     * @param key     Redis key
     * @param current 本次编码
     * @param ttlMs   TTL
     */
    private static void applyString(List<FlushOp> ops, LedgerDelta delta, FlushLedger ledger, String key,
                                    String current, long ttlMs) {
        if (ledger.changed(key, current)) {
            ops.add(FlushOp.set(key, current, ttlMs));
        }
        delta.confirmString(key, current);
    }

    /**
     * 本轮待写报文的 Redis 改动；不计入 flush-max-bytes。
     *
     * @param turn 本轮视图
     * @param keys 会话 Redis key
     * @return 报文 SET / DEL；无活动任务时为空列表
     */
    private List<FlushOp> payloadOps(DefaultTurnScope turn, SessionKeys keys) {
        return turn.task().map(task -> {
            List<FlushOp> ops = new ArrayList<>();
            String taskId = task.getTaskId();
            long ttlMs = groupTtl.of(task).toMillis();
            Set<String> protectedPayloadNodes = upstreamProtectedNodes(turn);
            turn.payloads().forEach((id, payload) -> writePayload(ops, keys, turn, taskId, id, payload,
                protectedPayloadNodes, ttlMs));
            return ops;
        }).orElseGet(List::of);
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
}
