package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.state.repo.PayloadRepository;
import com.g2rain.agent.runtime.engine.store.FlushOp;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 占位写入、整组 TTL、收尾原子化：T11、T13、V5～V7、W1～W5。
 *
 * @author alpha
 * @since 2026/11/22
 */
class TurnFlushPlanTest {

    private static final String ORGAN = "10001";

    private static final String USER = "20002";

    private static final String CONV = "conv-1";

    private static final String TASK = "task-1";

    private static final String TRACE = "trace-1";

    private static final String N1 = "n1";

    private static final String N2 = "n2";

    private static final String N3 = "n3";

    private MemoryStateStore store;

    private RuntimeProperties properties;

    private TurnFlushPlan plan;

    @BeforeEach
    void setUp() {
        store = new MemoryStateStore();
        properties = new RuntimeProperties();
        SpaceCodec spaces = new SpaceCodec(mock(PayloadRepository.class), properties);
        GroupTtl groupTtl = new GroupTtl(properties);
        plan = new TurnFlushPlan(store, properties, spaces, groupTtl);
    }

    /** T11：3 节点只打开 1 个；断点/前情 SET ""；MARK 齐全；末次 TTL 正确。 */
    @Test
    void t11_ops_marksAndTtlForAllNodes() {
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph(N1, N2, N3));
        turn.attach(new DefaultNodeScope(turn, N1, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0));

        List<FlushOp> ops = plan.ops(turn, SessionKeys.of(turn));
        SessionKeys keys = SessionKeys.of(turn);

        assertThat(findSet(ops, keys.checkpoint(TASK))).isEqualTo("");
        assertThat(findSet(ops, keys.prior(TASK))).isEqualTo("");
        assertThat(hasMark(ops, keys.shared(TASK))).isTrue();
        assertThat(hasMark(ops, keys.writes(TASK))).isTrue();
        assertThat(hasMark(ops, keys.space(TASK, N1))).isTrue();
        assertThat(hasMark(ops, keys.evidence(TASK, N1))).isTrue();
        assertThat(hasMark(ops, keys.space(TASK, N2))).isTrue();
        assertThat(hasMark(ops, keys.evidence(TASK, N2))).isTrue();
        assertThat(hasMark(ops, keys.space(TASK, N3))).isTrue();
        assertThat(hasMark(ops, keys.evidence(TASK, N3))).isTrue();

        long runningMs = properties.getState().getTaskTtlRunning().toMillis();
        long sentinelMs = runningMs - properties.getState().getSentinelGap().toMillis();
        Map<String, Long> lastTtl = lastExpireTtl(ops);
        List<String> groupKeys = keys.group(TASK, turn.graph());
        for (int i = 0, j = groupKeys.size(); i < j; i++) {
            String key = groupKeys.get(i);
            long expected = Objects.equals(key, keys.metadata(TASK)) ? sentinelMs : runningMs;
            assertThat(lastTtl.get(key)).as(key).isEqualTo(expected);
        }
        assertThat(Duration.ofMillis(sentinelMs))
            .isEqualTo(Duration.ofHours(24).minus(Duration.ofMinutes(10)));
    }

    /** T13：finish 删除整组 + 报文；只 flush 一次。 */
    @Test
    void t13_finish_deletesGroupAndPayloads() {
        TaskGraph graph = graph(N1, N2);
        DefaultTurnScope turn = turn(TaskStatus.SUCCEEDED, graph);
        seedVersion(turn);
        turn.putPayload("p1", JsonNodeFactory.instance.objectNode());
        turn.putPayload("p2", JsonNodeFactory.instance.objectNode());
        TaskRecord record = recordOf(TASK, TaskStatus.SUCCEEDED);

        plan.finish(turn, Map.of(TASK, record));

        SessionKeys keys = SessionKeys.of(turn);
        Set<String> deleted = deletedKeys(store.lastFlushOps());
        Set<String> expected = new HashSet<>(keys.group(TASK, graph));
        expected.add(keys.payload(TASK, "p1"));
        expected.add(keys.payload(TASK, "p2"));
        assertThat(deleted).containsAll(expected);
        assertThat(store.flushCount()).isEqualTo(1);
    }

    /** V5：状态改动很小 + 约 900KB 引号报文 → 不抛；flush 列表含报文 SET。 */
    @Test
    void v5_apply_largePayloadExcludedFromFlushCap() {
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph(N1));
        turn.attach(new DefaultNodeScope(turn, N1, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0));
        seedVersion(turn);
        String evidenceId = "big-payload";
        turn.putPayload(evidenceId, JsonNodeFactory.instance.textNode("\"".repeat(450_000)));

        plan.apply(turn);

        SessionKeys keys = SessionKeys.of(turn);
        assertThat(findSet(store.lastFlushOps(), keys.payload(TASK, evidenceId))).isNotNull();
    }

    /** V6：状态改动本身超过 flush-max-bytes → STATE_WRITE_FAILED。 */
    @Test
    void v6_apply_stateOverFlushMax_throws() {
        properties.getState().setFlushMaxBytes(64);
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph(N1, N2, N3));
        turn.attach(new DefaultNodeScope(turn, N1, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0));
        seedVersion(turn);

        assertThatThrownBy(() -> plan.apply(turn))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(RuntimeErrorCode.STATE_WRITE_FAILED.code());
    }

    /** V7：ops 不含报文 SET；apply 提交的列表含报文 SET。 */
    @Test
    void v7_ops_excludesPayloadSet_applyIncludes() {
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph(N1));
        turn.attach(new DefaultNodeScope(turn, N1, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0));
        seedVersion(turn);
        String evidenceId = "p-inline";
        turn.putPayload(evidenceId, JsonNodeFactory.instance.objectNode().put("ok", true));
        SessionKeys keys = SessionKeys.of(turn);
        String payloadKey = keys.payload(TASK, evidenceId);

        assertThat(findSet(plan.ops(turn, keys), payloadKey)).isNull();

        plan.apply(turn);

        assertThat(findSet(store.lastFlushOps(), payloadKey)).isNotNull();
    }

    /** W1：当前任务已结束 → 一次 flush；整组/报文 DEL、records、栅栏 HDEL；无任务组 HSET。 */
    @Test
    void w1_finish_terminalTask_singleFlushClosesGroup() {
        TaskGraph graph = graph(N1);
        DefaultTurnScope turn = turn(TaskStatus.SUCCEEDED, graph);
        turn.attach(new DefaultNodeScope(turn, N1, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0));
        seedVersion(turn);
        turn.putPayload("p1", JsonNodeFactory.instance.objectNode());
        TaskRecord record = recordOf(TASK, TaskStatus.SUCCEEDED);

        plan.finish(turn, Map.of(TASK, record));

        List<FlushOp> ops = store.lastFlushOps();
        SessionKeys keys = SessionKeys.of(turn);
        assertThat(store.flushCount()).isEqualTo(1);
        assertThat(deletedKeys(ops)).containsAll(keys.group(TASK, graph));
        assertThat(deletedKeys(ops)).contains(keys.payload(TASK, "p1"));
        assertThat(hasLpushTrim(ops, keys.records())).isTrue();
        assertThat(hasHdel(ops, keys.state(), "activeTaskId")).isTrue();
        assertThat(hasHdel(ops, keys.state(), "lastTraceId")).isTrue();
        assertThat(hasHsetKey(ops, keys.metadata(TASK))).isFalse();
    }

    /** W2：顶替旧任务、当前进行中 → 一次 flush；旧组 DEL；当前组写入；activeTaskId 为当前。 */
    @Test
    void w2_finish_replacedOld_keepsCurrentTask() {
        DefaultTurnScope turn = turn(TaskStatus.CANCELED, graph("old-n"));
        TaskMetadata neu = new TaskMetadata();
        neu.setTaskId("task-new");
        neu.setConversationId(CONV);
        neu.setStatus(TaskStatus.RUNNING);
        turn.start(neu, new Prior(List.of(), Map.of(), List.of()));
        turn.plan(graph(N1));
        turn.attach(new DefaultNodeScope(turn, N1, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0));
        seedVersion(turn);
        TaskRecord oldRecord = recordOf(TASK, TaskStatus.CANCELED);

        plan.finish(turn, Map.of(TASK, oldRecord));

        List<FlushOp> ops = store.lastFlushOps();
        SessionKeys keys = SessionKeys.of(turn);
        assertThat(store.flushCount()).isEqualTo(1);
        assertThat(deletedKeys(ops)).containsAll(keys.group(TASK, graph("old-n")));
        assertThat(hasLpushTrim(ops, keys.records())).isTrue();
        assertThat(hasHsetKey(ops, keys.metadata("task-new"))).isTrue();
        assertThat(hasHdel(ops, keys.state(), "activeTaskId")).isFalse();
        assertThat(hasHdel(ops, keys.state(), "lastTraceId")).isTrue();
    }

    /** W3：无结束任务 → 一次 flush；清 lastTraceId；不追加 records。 */
    @Test
    void w3_finish_noClosed_clearsLastTraceOnly() {
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph(N1));
        turn.attach(new DefaultNodeScope(turn, N1, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0));
        seedVersion(turn);

        plan.finish(turn, Map.of());

        List<FlushOp> ops = store.lastFlushOps();
        SessionKeys keys = SessionKeys.of(turn);
        assertThat(store.flushCount()).isEqualTo(1);
        assertThat(hasLpushTrim(ops, keys.records())).isFalse();
        assertThat(hasHdel(ops, keys.state(), "lastTraceId")).isTrue();
        assertThat(hasHdel(ops, keys.state(), "activeTaskId")).isFalse();
    }

    /** W4：records 缺已结束任务 → STATE_WRITE_FAILED，不 flush。 */
    @Test
    void w4_finish_missingRecord_throwsWithoutFlush() {
        DefaultTurnScope turn = turn(TaskStatus.SUCCEEDED, graph(N1));
        seedVersion(turn);

        assertThatThrownBy(() -> plan.finish(turn, Map.of()))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(RuntimeErrorCode.STATE_WRITE_FAILED.code());
        assertThat(store.flushCount()).isEqualTo(0);
    }

    /** W5：栅栏不符 → STATE_FENCED。 */
    @Test
    void w5_finish_fenceMismatch_throwsFenced() {
        DefaultTurnScope turn = turn(TaskStatus.RUNNING, graph(N1));
        store.hset(SessionKeys.of(turn).state(), "version", "999");

        assertThatThrownBy(() -> plan.finish(turn, Map.of()))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(RuntimeErrorCode.STATE_FENCED.code());
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
        return TurnScopes.create(ORGAN, USER, TRACE, fence, List.of(), List.of(), new HashMap<>(), meta, graph,
            null, new HashMap<>(), null, new HashMap<>(), 60);
    }

    private static TaskGraph graph(String... nodeIds) {
        TaskGraph graph = new TaskGraph();
        List<TaskNode> nodes = new ArrayList<>();
        for (int i = 0, j = nodeIds.length; i < j; i++) {
            TaskNode node = new TaskNode();
            node.setNodeId(nodeIds[i]);
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

    private static Set<String> deletedKeys(List<FlushOp> ops) {
        Set<String> deleted = new HashSet<>();
        for (FlushOp op : ops) {
            if (Objects.equals(op.op(), FlushOp.Op.DEL)) {
                deleted.add(op.key());
            }
        }
        return deleted;
    }

    private static boolean hasLpushTrim(List<FlushOp> ops, String key) {
        for (FlushOp op : ops) {
            if (Objects.equals(op.op(), FlushOp.Op.LPUSH_TRIM) && Objects.equals(op.key(), key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasHdel(List<FlushOp> ops, String key, String field) {
        for (FlushOp op : ops) {
            if (Objects.equals(op.op(), FlushOp.Op.HDEL) && Objects.equals(op.key(), key)
                && Objects.equals(op.field(), field)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasHsetKey(List<FlushOp> ops, String key) {
        for (FlushOp op : ops) {
            if (Objects.equals(op.op(), FlushOp.Op.HSET) && Objects.equals(op.key(), key)) {
                return true;
            }
        }
        return false;
    }

    private static @org.jspecify.annotations.Nullable String findSet(List<FlushOp> ops, String key) {
        String value = null;
        for (int i = 0, j = ops.size(); i < j; i++) {
            FlushOp op = ops.get(i);
            if (Objects.equals(op.op(), FlushOp.Op.SET) && Objects.equals(op.key(), key)) {
                value = op.value();
            }
        }
        return value;
    }

    private static boolean hasMark(List<FlushOp> ops, String key) {
        for (int i = 0, j = ops.size(); i < j; i++) {
            FlushOp op = ops.get(i);
            if (Objects.equals(op.op(), FlushOp.Op.HSET) && Objects.equals(op.key(), key)
                && Objects.equals(op.field(), HashCodec.MARK) && Objects.equals(op.value(), "")) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Long> lastExpireTtl(List<FlushOp> ops) {
        Map<String, Long> last = new HashMap<>();
        for (int i = 0, j = ops.size(); i < j; i++) {
            FlushOp op = ops.get(i);
            if (Objects.equals(op.op(), FlushOp.Op.EXPIRE) && Objects.nonNull(op.ttl())) {
                last.put(op.key(), op.ttl());
            }
        }
        return last;
    }
}
