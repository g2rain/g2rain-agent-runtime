package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.NodeSpace;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.engine.state.repo.CallRepository;
import com.g2rain.agent.runtime.engine.state.repo.ConversationRepository;
import com.g2rain.agent.runtime.engine.state.repo.GraphRepository;
import com.g2rain.agent.runtime.engine.state.repo.PayloadRepository;
import com.g2rain.agent.runtime.engine.state.repo.TaskRepository;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.spec.SideEffect;
import com.g2rain.agent.runtime.spec.SourceType;
import com.g2rain.agent.runtime.vo.ChatConversationVo;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.web.PrincipalContext;
import com.g2rain.common.web.PrincipalContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 哨兵定组与冷恢复规整：T1～T10、T12、T14。
 *
 * @author alpha
 * @since 2026/11/22
 */
@ExtendWith(MockitoExtension.class)
class TurnSessionHydrateTest {

    private static final JsonCodec JSON = JsonCodecFactory.instance();

    private static final long ORGAN = 10001L;

    private static final long USER = 20002L;

    private static final long VISITOR = 30003L;

    private static final String CONV = "conv-1";

    private static final String TASK = "task-1";

    private static final String TRACE = "trace-now";

    private static final String NODE_A = "node-a";

    private static final String NODE_B = "node-b";

    @Mock
    private ConversationRepository conversations;

    @Mock
    private TaskRepository tasks;

    @Mock
    private GraphRepository graphs;

    @Mock
    private CallRepository calls;

    @Mock
    private PayloadRepository payloads;

    private MemoryStateStore store;

    private SessionKeys keys;

    private TurnSessionHydrate hydrate;

    private TurnFlushPlan flushPlan;

    @BeforeEach
    void setUp() {
        store = new MemoryStateStore();
        keys = new SessionKeys(Long.toString(ORGAN), CONV);
        RuntimeProperties properties = new RuntimeProperties();
        SpaceCodec spaces = new SpaceCodec(payloads, properties);
        GroupTtl groupTtl = new GroupTtl(properties);
        hydrate = new TurnSessionHydrate(store, conversations, tasks, graphs, calls, spaces, groupTtl, properties);
        flushPlan = new TurnFlushPlan(store, properties, spaces, groupTtl);
        ChatConversationVo row = new ChatConversationVo();
        row.setUserId(USER);
        row.setActiveTaskId(TASK);
        row.setStateVersion(1L);
        lenient().when(conversations.find(CONV)).thenReturn(row);
        lenient().when(conversations.loadDialogue(anyString(), anyInt())).thenReturn(List.of());
        lenient().when(conversations.loadSessionShared(anyString())).thenReturn(Map.of());
        lenient().when(tasks.loadRecords(anyString(), anyInt())).thenReturn(List.of());
    }

    /** T1：热组断点为 ""，不回落 MySQL。 */
    @Test
    void t1_hotGroupEmptyCheckpoint_skipsMysql() {
        TaskCheckpoint mysqlCheckpoint = new TaskCheckpoint();
        mysqlCheckpoint.setCheckpointId("cp-mysql");
        seedHotGroup(TaskStatus.RUNNING, null, null, null, false);
        store.set(keys.checkpoint(TASK), "");
        lenient().when(tasks.loadCheckpoint(TASK)).thenReturn(mysqlCheckpoint);

        DefaultTurnScope turn = load();

        assertThat(turn.checkpoint()).isEmpty();
        verify(tasks, never()).loadCheckpoint(TASK);
    }

    /** T2：热组共享只有 MARK、前情为 ""，不回落 MySQL。 */
    @Test
    void t2_hotGroupEmptySharedAndPrior_skipsMysql() {
        seedHotGroup(TaskStatus.RUNNING, null, null, null, false);
        store.set(keys.prior(TASK), "");
        lenient().when(tasks.loadShared(TASK)).thenReturn(Map.of("k", sourced("v")));
        lenient().when(tasks.loadPrior(TASK)).thenReturn(new Prior(List.of(), Map.of(), List.of()));

        DefaultTurnScope turn = load();

        assertThat(turn.shared()).isEmpty();
        assertThat(turn.prior()).isEmpty();
        verify(tasks, never()).loadShared(TASK);
        verify(tasks, never()).loadPrior(TASK);
    }

    /** T3：热组打开节点时空间/证据只有 MARK，不回落 MySQL。 */
    @Test
    void t3_hotGroupOpenSpaceWithOnlyMark_skipsMysql() {
        seedHotGroup(TaskStatus.RUNNING, null, null, null, false);
        lenient().when(graphs.loadSpace(TASK, NODE_A))
            .thenReturn(new NodeSpace(Map.of(), Map.of(), Map.of("x", 1), 1, 0, List.of()));
        lenient().when(calls.loadNodeEvidence(TASK, NODE_A)).thenReturn(List.of(startedEvidence("e-mysql", 1)));

        DefaultTurnScope turn = load();
        DefaultNodeScope node = hydrate.openSpace(turn, keys, NODE_A);

        assertThat(node.inputs()).isEmpty();
        assertThat(node.evidence()).isEmpty();
        verify(graphs, never()).loadSpace(TASK, NODE_A);
        verify(calls, never()).loadNodeEvidence(TASK, NODE_A);
    }

    /** T4：淘汰未打开节点的空间 key → 冷组，整组删除并挂上全部节点。 */
    @Test
    void t4_evictedSpaceKey_becomesColdGroup() {
        seedHotGroup(TaskStatus.RUNNING, null, null, null, false);
        store.evict(keys.space(TASK, NODE_B));
        TaskMetadata mysqlMeta = metadata(TaskStatus.RUNNING);
        TaskGraph mysqlGraph = graph(NODE_A, NODE_B);
        when(tasks.loadMetadata(TASK)).thenReturn(mysqlMeta);
        when(graphs.load(TASK)).thenReturn(mysqlGraph);
        when(tasks.loadCheckpoint(TASK)).thenReturn(null);
        when(tasks.loadShared(TASK)).thenReturn(Map.of());
        when(tasks.loadPrior(TASK)).thenReturn(null);
        when(graphs.loadSpace(eq(TASK), anyString())).thenReturn(null);
        when(calls.loadNodeEvidence(eq(TASK), anyString())).thenReturn(List.of());

        DefaultTurnScope turn = load();

        List<String> groupKeys = keys.group(TASK, mysqlGraph);
        for (int i = 0, j = groupKeys.size(); i < j; i++) {
            assertThat(store.exists(groupKeys.get(i))).isFalse();
        }
        assertThat(turn.nodes().keySet()).containsExactlyInAnyOrder(NODE_A, NODE_B);
    }

    /** T5：冷组 SUSPENDED + STARTED 证据 → UNKNOWN（lastTraceId 为空）。 */
    @Test
    void t5_coldSuspendedStarted_becomesUnknown() {
        seedFence(null);
        TaskMetadata mysqlMeta = metadata(TaskStatus.SUSPENDED);
        TaskGraph mysqlGraph = graph(NODE_A);
        EvidenceHead started = startedEvidence("e1", 1);
        when(tasks.loadMetadata(TASK)).thenReturn(mysqlMeta);
        when(graphs.load(TASK)).thenReturn(mysqlGraph);
        when(tasks.loadCheckpoint(TASK)).thenReturn(null);
        when(tasks.loadShared(TASK)).thenReturn(Map.of());
        when(tasks.loadPrior(TASK)).thenReturn(null);
        when(graphs.loadSpace(TASK, NODE_A)).thenReturn(null);
        when(calls.loadNodeEvidence(TASK, NODE_A)).thenReturn(List.of(started));

        DefaultTurnScope turn = load();

        assertThat(turn.node(NODE_A).orElseThrow().evidence())
            .extracting(EvidenceHead::status)
            .containsExactly(EvidenceStatus.UNKNOWN);
    }

    /** T6：冷组 RUNNING + RUNNING 节点 + STARTED → READY / UNKNOWN。 */
    @Test
    void t6_coldRunningInterrupted_tidyNodesAndEvidence() {
        seedFence("old-trace");
        TaskNode running = node(NODE_A, NodeStatus.RUNNING);
        TaskGraph mysqlGraph = new TaskGraph();
        mysqlGraph.setNodes(List.of(running));
        when(tasks.loadMetadata(TASK)).thenReturn(metadata(TaskStatus.RUNNING));
        when(graphs.load(TASK)).thenReturn(mysqlGraph);
        when(tasks.loadCheckpoint(TASK)).thenReturn(null);
        when(tasks.loadShared(TASK)).thenReturn(Map.of());
        when(tasks.loadPrior(TASK)).thenReturn(null);
        when(graphs.loadSpace(TASK, NODE_A)).thenReturn(null);
        when(calls.loadNodeEvidence(TASK, NODE_A)).thenReturn(List.of(startedEvidence("e1", 1)));

        DefaultTurnScope turn = load();

        assertThat(turn.graph().find(NODE_A).getStatus()).isEqualTo(NodeStatus.READY);
        assertThat(turn.node(NODE_A).orElseThrow().evidence())
            .extracting(EvidenceHead::status)
            .containsExactly(EvidenceStatus.UNKNOWN);
    }

    /** T7：热组中断且任务 SUSPENDED，Redis STARTED → UNKNOWN。 */
    @Test
    void t7_hotInterruptedSuspended_startedBecomesUnknown() {
        seedHotGroup(TaskStatus.SUSPENDED, null, null, List.of(startedEvidence("e1", 1)), true);

        DefaultTurnScope turn = load();

        assertThat(turn.node(NODE_A).orElseThrow().evidence())
            .extracting(EvidenceHead::status)
            .containsExactly(EvidenceStatus.UNKNOWN);
    }

    /** T8：热组未中断，不规整也不打开节点。 */
    @Test
    void t8_hotUninterrupted_skipsTidy() {
        seedHotGroup(TaskStatus.RUNNING, null, null, List.of(startedEvidence("e1", 1)), false);

        DefaultTurnScope turn = load();

        assertThat(turn.nodes()).isEmpty();
        assertThat(turn.graph().find(NODE_A).getStatus()).isEqualTo(NodeStatus.READY);
    }

    /** T9：冷组且 MySQL 无元数据 → 清空活动任务。 */
    @Test
    void t9_coldMissingMysqlMetadata_clearsActiveTask() {
        seedFence(null);
        when(tasks.loadMetadata(TASK)).thenReturn(null);

        DefaultTurnScope turn = load();

        assertThat(turn.conversation().activeTaskId()).isNull();
        assertThat(turn.task()).isEmpty();
    }

    /** W7：热组元数据为终态 → 清空 activeTaskId，无活动任务。 */
    @Test
    void w7_load_hotTerminal_clearsActiveTask() {
        seedHotGroup(TaskStatus.SUCCEEDED, null, null, null, false);

        DefaultTurnScope turn = load();

        assertThat(turn.conversation().activeTaskId()).isNull();
        assertThat(turn.task()).isEmpty();
    }

    /** W8：冷组 MySQL 元数据为终态 → 同 W7。 */
    @Test
    void w8_load_coldTerminal_clearsActiveTask() {
        seedFence(null);
        when(tasks.loadMetadata(TASK)).thenReturn(metadata(TaskStatus.SUCCEEDED));
        when(graphs.load(TASK)).thenReturn(graph(NODE_A));
        when(tasks.loadCheckpoint(TASK)).thenReturn(null);
        when(tasks.loadShared(TASK)).thenReturn(Map.of());
        when(tasks.loadPrior(TASK)).thenReturn(null);

        DefaultTurnScope turn = load();

        assertThat(turn.conversation().activeTaskId()).isNull();
        assertThat(turn.task()).isEmpty();
    }

    /** T10：热组 load 后淘汰证据 key，再 openSpace → STATE_UNAVAILABLE。 */
    @Test
    void t10_midTurnEvictEvidence_throwsUnavailable() {
        seedHotGroup(TaskStatus.RUNNING, null, null, null, false);
        DefaultTurnScope turn = load();
        store.evict(keys.evidence(TASK, NODE_A));

        assertThatThrownBy(() -> hydrate.openSpace(turn, keys, NODE_A))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(RuntimeErrorCode.STATE_UNAVAILABLE.code());
    }

    /** T12：冷组 load → flush → 再 load 判为热组。 */
    @Test
    void t12_coldFlushThenLoad_becomesHot() {
        seedFence(null);
        TaskMetadata mysqlMeta = metadata(TaskStatus.RUNNING);
        TaskGraph mysqlGraph = graph(NODE_A, NODE_B);
        when(tasks.loadMetadata(TASK)).thenReturn(mysqlMeta);
        when(graphs.load(TASK)).thenReturn(mysqlGraph);
        when(tasks.loadCheckpoint(TASK)).thenReturn(null);
        when(tasks.loadShared(TASK)).thenReturn(Map.of("k", sourced("from-mysql")));
        when(tasks.loadPrior(TASK)).thenReturn(null);
        when(graphs.loadSpace(eq(TASK), anyString())).thenReturn(null);
        when(calls.loadNodeEvidence(eq(TASK), anyString())).thenReturn(List.of());

        DefaultTurnScope first = load();
        flushPlan.finish(first, Map.of());
        clearInvocations(tasks, graphs, calls);

        DefaultTurnScope second = load();

        assertThat(second.shared()).containsKey("k");
        assertThat(second.shared().get("k").value()).isEqualTo("from-mysql");
        assertThat(second.nodes()).isEmpty();
        verify(tasks, never()).loadMetadata(TASK);
        verify(tasks, never()).loadCheckpoint(TASK);
        verify(graphs, never()).load(TASK);
        verify(graphs, never()).loadSpace(eq(TASK), anyString());
    }

    /** T14：HashCodec.toObject 只有 MARK → null。 */
    @Test
    void t14_hashCodecOnlyMark_returnsNull() {
        assertThat(HashCodec.toObject(Map.of(HashCodec.MARK, ""), TaskMetadata.class)).isNull();
    }

    /** T15：本轮新建任务，图中有节点，直接 openSpace → 空节点。 */
    @Test
    void t15_newTaskOpenSpace_returnsEmptyNode() {
        TaskMetadata meta = metadata(TaskStatus.RUNNING);
        TaskGraph newGraph = graph(NODE_A);
        ConversationFence fence = new ConversationFence(CONV, Long.toString(USER), TASK, 1L, TRACE,
            System.currentTimeMillis());
        DefaultTurnScope turn = TurnScopes.create(Long.toString(ORGAN), Long.toString(USER), TRACE, fence,
            List.of(), List.of(), Map.of(), meta, newGraph, null, Map.of(), null, Map.of(), 60);

        DefaultNodeScope node = hydrate.openSpace(turn, keys, NODE_A);

        assertThat(node.inputs()).isEmpty();
        assertThat(node.evidence()).isEmpty();
        assertThat(turn.verified(NODE_A)).isFalse();
    }

    /** T16：热组加载后新增节点可打开为空；已校验节点被淘汰仍抛错。 */
    @Test
    void t16_hotLoadNewNodeEmpty_verifiedEvictStillThrows() {
        seedHotGroup(TaskStatus.RUNNING, null, null, null, false);
        DefaultTurnScope turn = load();
        String nodeC = "node-c";
        turn.graph().getNodes().add(node(nodeC, NodeStatus.READY));

        DefaultNodeScope created = hydrate.openSpace(turn, keys, nodeC);
        assertThat(created.inputs()).isEmpty();
        assertThat(created.evidence()).isEmpty();

        store.evict(keys.evidence(TASK, NODE_A));
        assertThatThrownBy(() -> hydrate.openSpace(turn, keys, NODE_A))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(RuntimeErrorCode.STATE_UNAVAILABLE.code());
    }

    @Test
    void employeeCanContinueVisitorConversationWithoutChangingOwner() {
        ChatConversationVo row = new ChatConversationVo();
        row.setUserId(VISITOR);
        row.setStateVersion(0L);
        when(conversations.find(CONV)).thenReturn(row);

        DefaultTurnScope turn = load(VISITOR);
        flushPlan.apply(turn);

        String storedOwnerId = JSON.str2obj(store.hGetAll(keys.state()).get("userId"), String.class);
        assertThat(turn.conversation().userId()).isEqualTo(Long.toString(VISITOR));
        assertThat(storedOwnerId).isEqualTo(Long.toString(VISITOR));
        assertThat(row.getUserId()).isEqualTo(VISITOR);
        verify(conversations, never()).create(eq(CONV), eq(USER), isNull());
    }

    @Test
    void employeeWithoutVisitorIdCannotContinueVisitorConversation() {
        ChatConversationVo row = new ChatConversationVo();
        row.setUserId(VISITOR);
        row.setStateVersion(0L);
        when(conversations.find(CONV)).thenReturn(row);

        assertThatThrownBy(this::load)
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(RuntimeErrorCode.CONVERSATION_FORBIDDEN.code());
    }

    @Test
    void newConversationKeepsEmployeeAsOwnerEvenWithVisitorContext() {
        when(conversations.find(CONV)).thenReturn(null);

        DefaultTurnScope turn = load(VISITOR);

        assertThat(turn.conversation().userId()).isEqualTo(Long.toString(USER));
        verify(conversations).create(eq(CONV), eq(USER), isNull());
    }

    /** L13：热组加载不读节点空间/证据；元数据、图、共享各只 hGetAll 一次。 */
    @Test
    void l13_hotLoad_noNodeHGetAll_taskHashesOnce() {
        seedHotGroup(TaskStatus.RUNNING, null, null, null, false);
        store.clearHGetAllCounts();

        load();

        assertThat(store.hGetAllCount(keys.metadata(TASK))).isEqualTo(1);
        assertThat(store.hGetAllCount(keys.graph(TASK))).isEqualTo(1);
        assertThat(store.hGetAllCount(keys.shared(TASK))).isEqualTo(1);
        assertThat(store.hGetAllCount(keys.space(TASK, NODE_A))).isEqualTo(0);
        assertThat(store.hGetAllCount(keys.evidence(TASK, NODE_A))).isEqualTo(0);
        assertThat(store.hGetAllCount(keys.space(TASK, NODE_B))).isEqualTo(0);
        assertThat(store.hGetAllCount(keys.evidence(TASK, NODE_B))).isEqualTo(0);
        assertThat(store.hGetAllCount(keys.writes(TASK))).isEqualTo(0);
    }

    private DefaultTurnScope load() {
        return load(null);
    }

    private DefaultTurnScope load(Long visitorId) {
        TurnRequest request = new TurnRequest();
        request.setChannel("web");
        request.setConversationId(CONV);
        PrincipalContext context = PrincipalContext.of();
        context.setOrganId(ORGAN);
        context.setUserId(USER);
        context.setVisitorId(visitorId);
        context.setTraceId(TRACE);
        AtomicReference<DefaultTurnScope> ref = new AtomicReference<>();
        PrincipalContextHolder.runWith(context, () -> ref.set(hydrate.load(request)));
        return ref.get();
    }

    private void seedFence(@org.jspecify.annotations.Nullable String lastTraceId) {
        // Hash 字段值须是 JSON 文本（与 HashCodec / renew.lua 一致），不能写裸字符串
        store.hset(keys.state(), "userId", JSON.obj2str(JsonNodeFactory.instance.stringNode(Long.toString(USER))));
        store.hset(keys.state(), "activeTaskId", JSON.obj2str(JsonNodeFactory.instance.stringNode(TASK)));
        store.hset(keys.state(), "version", "1");
        store.hset(keys.state(), "updatedAt", Long.toString(System.currentTimeMillis()));
        if (Objects.nonNull(lastTraceId)) {
            store.hset(keys.state(), "lastTraceId", JSON.obj2str(JsonNodeFactory.instance.stringNode(lastTraceId)));
        }
    }

    private void seedHotGroup(TaskStatus status, @org.jspecify.annotations.Nullable TaskCheckpoint checkpoint,
                              @org.jspecify.annotations.Nullable Prior prior,
                              @org.jspecify.annotations.Nullable List<EvidenceHead> nodeAEvidence,
                              boolean interrupted) {
        seedFence(interrupted ? "old-trace" : null);
        TaskMetadata meta = metadata(status);
        TaskGraph graph = graph(NODE_A, NODE_B);
        StateCodec.metadataHash(meta)
            .forEach((field, value) -> store.hset(keys.metadata(TASK), field, value));
        StateCodec.graphHash(graph).forEach((field, value) -> store.hset(keys.graph(TASK), field, value));
        store.set(keys.checkpoint(TASK), Objects.isNull(checkpoint) ? "" : JSON.obj2str(checkpoint));
        store.hset(keys.shared(TASK), HashCodec.MARK, "");
        store.set(keys.prior(TASK), Objects.isNull(prior) ? "" : JSON.obj2str(prior));
        store.hset(keys.writes(TASK), HashCodec.MARK, "");
        store.hset(keys.space(TASK, NODE_A), HashCodec.MARK, "");
        store.hset(keys.evidence(TASK, NODE_A), HashCodec.MARK, "");
        store.hset(keys.space(TASK, NODE_B), HashCodec.MARK, "");
        store.hset(keys.evidence(TASK, NODE_B), HashCodec.MARK, "");
        if (Objects.nonNull(nodeAEvidence)) {
            for (int i = 0, j = nodeAEvidence.size(); i < j; i++) {
                EvidenceHead head = nodeAEvidence.get(i);
                store.hset(keys.evidence(TASK, NODE_A), head.evidenceId(), JSON.obj2str(head));
            }
        }
    }

    private static TaskMetadata metadata(TaskStatus status) {
        TaskMetadata meta = new TaskMetadata();
        meta.setTaskId(TASK);
        meta.setConversationId(CONV);
        meta.setStatus(status);
        return meta;
    }

    private static TaskGraph graph(String... nodeIds) {
        TaskGraph graph = new TaskGraph();
        List<TaskNode> nodes = new ArrayList<>();
        for (int i = 0, j = nodeIds.length; i < j; i++) {
            nodes.add(node(nodeIds[i], NodeStatus.READY));
        }
        graph.setNodes(nodes);
        return graph;
    }

    private static TaskNode node(String nodeId, NodeStatus status) {
        TaskNode node = new TaskNode();
        node.setNodeId(nodeId);
        node.setStatus(status);
        return node;
    }

    private static EvidenceHead startedEvidence(String id, int round) {
        return new EvidenceHead(id, "tool.write", SideEffect.WRITE, EvidenceStatus.STARTED, null, null, null,
            FailureKind.NONE, "idem-1", "hash", null, null, null, null, null, round, "old-trace", null);
    }

    private static Sourced<?> sourced(String value) {
        return new Sourced<>(value, new Source(SourceType.USER, "m1", ""), false);
    }
}
