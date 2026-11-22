package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.task.EntityEntry;
import com.g2rain.agent.runtime.domain.state.task.NodeSpace;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.state.repo.PayloadRepository;
import com.g2rain.agent.runtime.engine.store.FlushOp;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.spec.SourceType;
import com.g2rain.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TurnFlushPlan} 按变更写入用例 L1～L14。
 *
 * @author alpha
 * @since 2026/11/22
 */
class TurnFlushPlanChangeTest {

    private static final String ORGAN = "10001";

    private static final String USER = "20002";

    private static final String CONV = "conv-1";

    private static final String TASK = "task-1";

    private static final String TRACE = "trace-1";

    private static final String N1 = "n1";

    private static final String N2 = "n2";

    private MemoryStateStore store;

    private PayloadRepository payloads;

    private RuntimeProperties properties;

    private TurnFlushPlan plan;

    @BeforeEach
    void setUp() {
        store = new MemoryStateStore();
        payloads = mock(PayloadRepository.class);
        properties = new RuntimeProperties();
        SpaceCodec spaces = new SpaceCodec(payloads, properties);
        plan = new TurnFlushPlan(store, properties, spaces, new GroupTtl(properties));
    }

    /** L1：热组口径登记后不改动 → 对比结构无写入，只有 EXPIRE；栅栏每次全量写。 */
    @Test
    void l1_seededHot_noChanges_onlyExpire() {
        TaskGraph graph = graph(N1, N2);
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph);
        seedAsHot(turn);
        SessionKeys keys = SessionKeys.of(turn);

        List<FlushOp> ops = plan.ops(turn, keys);

        assertThat(diffOps(ops, keys)).isEmpty();
        assertThat(ops).isNotEmpty().allMatch(op ->
            Objects.equals(op.op(), FlushOp.Op.EXPIRE) || Objects.equals(op.key(), keys.state()));
    }

    /** L2：只改一个节点 outputs → 只对该节点空间 HSET outputs。 */
    @Test
    void l2_onlyOutputsChange_hsetsThatField() {
        TaskGraph graph = graph(N1, N2);
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph);
        DefaultNodeScope node = new DefaultNodeScope(turn, N1, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0);
        turn.attach(node);
        seedAsHot(turn);
        node.output("body", new Sourced<>("x", new Source(SourceType.USER, "m1", ""), false));

        List<FlushOp> ops = plan.ops(turn, SessionKeys.of(turn));
        SessionKeys keys = SessionKeys.of(turn);

        assertThat(diffOps(ops, keys)).hasSize(1);
        FlushOp only = diffOps(ops, keys).getFirst();
        assertThat(only.op()).isEqualTo(FlushOp.Op.HSET);
        assertThat(only.key()).isEqualTo(keys.space(TASK, N1));
        assertThat(only.field()).isEqualTo("outputs");
    }

    /** L3：淘汰实体 → entities HDEL。 */
    @Test
    void l3_removeEntity_hdelsField() {
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph(N1));
        turn.entityIndex().put("order:1", new EntityEntry("A", JsonNodeFactory.instance.objectNode(), TASK, null, 1L, 1));
        seedAsHot(turn);
        turn.entityIndex().remove("order:1");

        List<FlushOp> ops = plan.ops(turn, SessionKeys.of(turn));
        SessionKeys keys = SessionKeys.of(turn);

        assertThat(diffOps(ops, keys)).hasSize(1);
        FlushOp only = diffOps(ops, keys).getFirst();
        assertThat(only.op()).isEqualTo(FlushOp.Op.HDEL);
        assertThat(only.key()).isEqualTo(keys.entities());
        assertThat(only.field()).isEqualTo("order:1");
    }

    /** L4：节点空间某字段从有值变为编码结果中不存在 → HDEL。 */
    @Test
    void l4_spaceFieldMissing_hdels() {
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph(N1));
        turn.attach(new DefaultNodeScope(turn, N1, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0));
        seedAsHot(turn);
        SessionKeys keys = SessionKeys.of(turn);
        String spaceKey = keys.space(TASK, N1);
        Map<String, String> withGone = new LinkedHashMap<>();
        withGone.put(HashCodec.MARK, "");
        withGone.putAll(HashCodec.toHash(new NodeSpace(Map.of(), Map.of(), Map.of(), 0, 0, List.of())));
        withGone.put("gone", "1");
        turn.ledger().seedHash(spaceKey, withGone);

        List<FlushOp> ops = plan.ops(turn, SessionKeys.of(turn));

        assertThat(diffOps(ops, keys)).anyMatch(op ->
            Objects.equals(op.op(), FlushOp.Op.HDEL)
                && Objects.equals(op.key(), spaceKey)
                && Objects.equals(op.field(), "gone"));
    }

    /** L5：超长字段已登记且不变 → 连续 flush 不落库。 */
    @Test
    void l5_unchangedSpillField_neverSaves() {
        String longText = "y".repeat(properties.getState().getSpaceFieldMaxBytes() + 10);
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph(N1));
        DefaultNodeScope node = new DefaultNodeScope(turn, N1, Map.of(),
            Map.of("body", new Sourced<>(longText, new Source(SourceType.USER, "m1", ""), false)),
            Map.of(), List.of(), List.of(), 0, 0);
        turn.attach(node);
        seedAsHot(turn);
        seedVersion(turn);

        plan.apply(turn);
        plan.apply(turn);

        verify(payloads, never()).saveSpaceField(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
    }

    /** L6：flush 被拒不 commit；再次 flush 仍发出相同改动。 */
    @Test
    void l6_fencedFlush_doesNotCommit_retriesSame() {
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph(N1));
        turn.attach(new DefaultNodeScope(turn, N1, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0));
        // 不登记 ledger → 首次必有写入；不写 Redis version → flush 失败
        List<FlushOp> expected = contentOps(plan.ops(turn, SessionKeys.of(turn)));
        assertThat(expected).isNotEmpty();

        assertThatThrownBy(() -> plan.apply(turn))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(RuntimeErrorCode.STATE_FENCED.code());

        assertThat(contentOps(plan.ops(turn, SessionKeys.of(turn))))
            .usingRecursiveFieldByFieldElementComparator()
            .containsExactlyElementsOf(expected);
    }

    /** L7：新建任务第一次全量写占位，第二次对比结构不再写占位。 */
    @Test
    void l7_newTask_secondFlushSkipsMarks() {
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph(N1, N2));
        seedVersion(turn);
        SessionKeys keys = SessionKeys.of(turn);

        plan.apply(turn);
        assertThat(hasMark(store.lastFlushOps(), keys.space(TASK, N1))).isTrue();
        assertThat(hasMark(store.lastFlushOps(), keys.space(TASK, N2))).isTrue();

        plan.apply(turn);
        assertThat(hasMark(store.lastFlushOps(), keys.space(TASK, N1))).isFalse();
        assertThat(hasMark(store.lastFlushOps(), keys.space(TASK, N2))).isFalse();
        assertThat(diffOps(store.lastFlushOps(), keys)).isEmpty();
    }

    /** L8：finish 删除任务组后，ledger 不再持有该组 key。 */
    @Test
    void l8_finish_removesGroupKeysFromLedger() {
        TaskGraph graph = graph(N1);
        DefaultTurnScope turn = turn(TaskStatus.SUCCEEDED, graph);
        seedAsHot(turn);
        seedVersion(turn);
        SessionKeys keys = SessionKeys.of(turn);
        assertThat(turn.ledger().hasHash(keys.metadata(TASK))).isTrue();

        plan.finish(turn, Map.of(TASK, recordOf(TASK, TaskStatus.SUCCEEDED)));

        assertThat(turn.ledger().hasHash(keys.metadata(TASK))).isFalse();
        assertThat(turn.ledger().hasHash(keys.space(TASK, N1))).isFalse();
    }

    /** L9：无 state 登记（模拟 Redis 缺失、栅栏自 MySQL 回灌）→ flush 仍 HSET userId / activeTaskId。 */
    @Test
    void l9_missingState_mysqlFence_hsetsUserAndActiveTask() {
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph(N1));
        seedAsHot(turn);
        SessionKeys keys = SessionKeys.of(turn);

        List<FlushOp> ops = plan.ops(turn, keys);

        assertThat(ops).anyMatch(op ->
            Objects.equals(op.op(), FlushOp.Op.HSET)
                && Objects.equals(op.key(), keys.state())
                && Objects.equals(op.field(), "userId"));
        assertThat(ops).anyMatch(op ->
            Objects.equals(op.op(), FlushOp.Op.HSET)
                && Objects.equals(op.key(), keys.state())
                && Objects.equals(op.field(), "activeTaskId"));
    }

    /** L10：clearLastTrace 后 flush → lastTraceId HDEL。 */
    @Test
    void l10_clearLastTrace_hdelsLastTraceId() {
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph(N1));
        seedAsHot(turn);
        turn.clearLastTrace();
        SessionKeys keys = SessionKeys.of(turn);

        List<FlushOp> ops = plan.ops(turn, keys);

        assertThat(ops).anyMatch(op ->
            Objects.equals(op.op(), FlushOp.Op.HDEL)
                && Objects.equals(op.key(), keys.state())
                && Objects.equals(op.field(), "lastTraceId"));
    }

    /** L11：已校验的未打开节点 A 有产出与证据 → flush 不触碰 A 的空间/证据。 */
    @Test
    void l11_verifiedUnopenedNode_noSpaceOrEvidenceOps() {
        TaskGraph graph = graph(N1, N2);
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph);
        DefaultNodeScope b = new DefaultNodeScope(turn, N2, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0);
        turn.attach(b);
        seedAsHot(turn);
        SessionKeys keys = SessionKeys.of(turn);
        String spaceA = keys.space(TASK, N1);
        String evidenceA = keys.evidence(TASK, N1);

        List<FlushOp> ops = plan.ops(turn, keys);

        assertThat(diffOps(ops, keys)).noneMatch(op ->
            Objects.equals(op.key(), spaceA) || Objects.equals(op.key(), evidenceA));
    }

    /** L12：写索引里有未打开节点的条目 → flush 不对写索引发 HDEL。 */
    @Test
    void l12_writesFromUnopened_noHdel() {
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph(N1, N2));
        turn.attach(new DefaultNodeScope(turn, N2, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0));
        seedAsHot(turn);
        SessionKeys keys = SessionKeys.of(turn);
        Map<String, String> writes = new LinkedHashMap<>();
        writes.put(HashCodec.MARK, "");
        writes.put("tool|idem-a", "{\"evidenceId\":\"e-a\",\"status\":\"SUCCEEDED\",\"businessOk\":true}");
        turn.ledger().seedHash(keys.writes(TASK), writes);

        List<FlushOp> ops = plan.ops(turn, keys);

        assertThat(ops).noneMatch(op ->
            Objects.equals(op.op(), FlushOp.Op.HDEL) && Objects.equals(op.key(), keys.writes(TASK)));
    }

    /** L14：本轮新加未校验节点，连续 flush 两次 → 第一次写 MARK，第二次不写。 */
    @Test
    void l14_newUnverifiedNode_markOnce() {
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph(N1));
        seedVersion(turn);
        SessionKeys keys = SessionKeys.of(turn);

        plan.apply(turn);
        assertThat(hasMark(store.lastFlushOps(), keys.space(TASK, N1))).isTrue();
        assertThat(hasMark(store.lastFlushOps(), keys.evidence(TASK, N1))).isTrue();

        plan.apply(turn);
        assertThat(hasMark(store.lastFlushOps(), keys.space(TASK, N1))).isFalse();
        assertThat(hasMark(store.lastFlushOps(), keys.evidence(TASK, N1))).isFalse();
    }

    private void seedAsHot(DefaultTurnScope turn) {
        SessionKeys keys = SessionKeys.of(turn);
        FlushLedger ledger = turn.ledger();
        List<String> verifiedIds = new ArrayList<>();
        for (TaskNode node : turn.graph().getNodes()) {
            verifiedIds.add(node.getNodeId());
        }
        turn.markVerified(verifiedIds);
        ledger.seedHash(keys.entities(), StateCodec.entitiesHash(turn.entities()));
        ledger.seedHash(keys.sessionShared(), StateCodec.sharedHash(turn.sessionShared(), false));
        TaskMetadata meta = turn.task().orElseThrow();
        ledger.seedHash(keys.metadata(TASK), StateCodec.metadataHash(meta));
        ledger.seedHash(keys.graph(TASK), StateCodec.graphHash(turn.graph()));
        ledger.seedHash(keys.shared(TASK), StateCodec.sharedHash(turn.shared(), true));
        ledger.seedString(keys.checkpoint(TASK), StateCodec.checkpointValue(turn.checkpoint().orElse(null)));
        ledger.seedString(keys.prior(TASK), StateCodec.priorValue(turn.prior().orElse(null)));
        // 与 hydrate 一致：只登记已打开节点；写索引不在加载时登记
        for (TaskNode node : turn.graph().getNodes()) {
            String nodeId = node.getNodeId();
            if (!turn.nodes().containsKey(nodeId)) {
                continue;
            }
            DefaultNodeScope opened = turn.nodes().get(nodeId);
            NodeSpace space = new NodeSpace(opened.inputs(), opened.outputs(), opened.state(),
                opened.rounds(), opened.tokens(), opened.actions());
            Map<String, String> plain = new LinkedHashMap<>();
            plain.put(HashCodec.MARK, "");
            plain.putAll(HashCodec.toHash(space));
            ledger.seedHash(keys.space(TASK, nodeId), plain);
            ledger.seedHash(keys.evidence(TASK, nodeId), StateCodec.evidenceHash(opened.evidence()));
        }
    }

    private void seedVersion(DefaultTurnScope turn) {
        store.hset(SessionKeys.of(turn).state(), "version", Long.toString(turn.conversation().version()));
    }

    private static DefaultTurnScope turn(TaskStatus status, TaskGraph graph) {
        TaskMetadata meta = new TaskMetadata();
        meta.setTaskId(TASK);
        meta.setConversationId(CONV);
        meta.setStatus(status);
        ConversationFence fence = new ConversationFence(CONV, USER, TASK, 3L, TRACE, System.currentTimeMillis());
        return new DefaultTurnScope(ORGAN, USER, TRACE, fence, List.of(), List.of(), new HashMap<>(), meta, graph,
            null, new HashMap<>(), null, new HashMap<>(), 60);
    }

    private static TaskGraph graph(String... nodeIds) {
        TaskGraph graph = new TaskGraph();
        List<TaskNode> nodes = new ArrayList<>();
        for (String nodeId : nodeIds) {
            TaskNode node = new TaskNode();
            node.setNodeId(nodeId);
            node.setStatus(NodeStatus.READY);
            nodes.add(node);
        }
        graph.setNodes(nodes);
        return graph;
    }

    private static TaskRecord recordOf(String taskId, TaskStatus status) {
        return new TaskRecord(taskId, "agent", "goal", status, "done", null, null, null, List.of(), List.of(),
            System.currentTimeMillis());
    }

    private static List<FlushOp> contentOps(List<FlushOp> ops) {
        return ops.stream()
            .filter(op -> op.op() == FlushOp.Op.HSET || op.op() == FlushOp.Op.HDEL || op.op() == FlushOp.Op.SET
                || op.op() == FlushOp.Op.DEL || op.op() == FlushOp.Op.LPUSH_TRIM)
            .collect(Collectors.toList());
    }

    /** 对比结构改动：去掉会话栅栏（每次全量写，不参与对比）。 */
    private static List<FlushOp> diffOps(List<FlushOp> ops, SessionKeys keys) {
        return contentOps(ops).stream()
            .filter(op -> !Objects.equals(op.key(), keys.state()))
            .collect(Collectors.toList());
    }

    private static boolean hasMark(List<FlushOp> ops, String key) {
        for (FlushOp op : ops) {
            if (Objects.equals(op.op(), FlushOp.Op.HSET) && Objects.equals(op.key(), key)
                && Objects.equals(op.field(), HashCodec.MARK) && Objects.equals(op.value(), "")) {
                return true;
            }
        }
        return false;
    }
}
