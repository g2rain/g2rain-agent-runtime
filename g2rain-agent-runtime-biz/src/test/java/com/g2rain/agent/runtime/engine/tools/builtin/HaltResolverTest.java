package com.g2rain.agent.runtime.engine.tools.builtin;


import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.HaltReason;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.state.TurnScopes;
import com.g2rain.agent.runtime.engine.tools.ToolRejectedException;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.spec.ArgSpec;
import com.g2rain.agent.runtime.spec.SideEffect;
import com.g2rain.agent.runtime.spec.SourceType;
import com.g2rain.agent.runtime.spec.ToolSpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link HaltResolver} 停顿原因判定矩阵与停顿期间约束验收。
 *
 * @author alpha
 * @since 2026/11/21
 */
class HaltResolverTest {

    private static final String DENIED_TOOL = "delete";

    private static final List<EvidenceHead> NONE = List.of();

    private static final List<EvidenceHead> FOUND = List.of(head(EvidenceStatus.SUCCESS, null, false, Map.of("id", 1)));

    private static final List<EvidenceHead> MANY = List.of(head(EvidenceStatus.SUCCESS, null, false, Map.of("id", 3)));

    private static final List<EvidenceHead> EMPTY = List.of(head(EvidenceStatus.SUCCESS, null, true, null));

    private static final List<EvidenceHead> FAILED = List.of(head(EvidenceStatus.SUCCESS, false, null, null));

    /**
     * 拒绝待决定 × 最近一次调用 × 待补参数 × 有无契约 → 停顿原因（{@code null} 表示拒绝追问）。
     */
    static Stream<Arguments> followUpMatrix() {
        return Stream.of(
            Arguments.of("拒绝后追问，非参数名也放行", true, FOUND, List.of("处理方式"), true, HaltReason.DENIED),
            Arguments.of("拒绝后追问，不带参数", true, NONE, List.of(), true, HaltReason.DENIED),
            Arguments.of("多条候选，不带参数", false, MANY, List.of(), true, HaltReason.AMBIGUOUS),
            Arguments.of("多条候选，带查询来源参数", false, MANY, List.of("id"), true, HaltReason.AMBIGUOUS),
            Arguments.of("查询为空", false, EMPTY, List.of(), true, HaltReason.UNRESOLVED),
            Arguments.of("业务失败", false, FAILED, List.of(), true, HaltReason.UNRESOLVED),
            Arguments.of("用户来源参数", false, FOUND, List.of("title"), true, HaltReason.MISSING_INPUT),
            Arguments.of("用户来源参数按叶子名匹配", false, NONE, List.of("body.title"), true, HaltReason.MISSING_INPUT),
            Arguments.of("无契约不带参数", false, NONE, List.of(), false, HaltReason.UNRESOLVED),
            Arguments.of("无契约带任意参数", false, NONE, List.of("处理方式"), false, HaltReason.MISSING_INPUT),
            Arguments.of("没有依据", false, FOUND, List.of(), true, null),
            Arguments.of("查询来源参数但没有候选", false, NONE, List.of("id"), true, null),
            Arguments.of("非参数名", false, FOUND, List.of("处理方式"), true, null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("followUpMatrix")
    void followUp(String name, boolean denied, List<EvidenceHead> evidence, List<String> fields, boolean withContract,
                  @Nullable HaltReason expected) {
        Map<String, Object> notes = new HashMap<>();
        if (denied) {
            notes.put(NodeNoteKey.GUARD_DENIAL.wire(), DENIED_TOOL);
        }

        ToolExecutionContext ctx = context(notes, evidence, withContract ? contract() : null);
        if (Objects.isNull(expected)) {
            ToolRejectedException ex = assertThrows(ToolRejectedException.class, () -> HaltResolver.followUp(ctx, fields));
            assertEquals(RuntimeErrorCode.FOLLOW_UP_UNGROUNDED, ex.error());
            return;
        }

        assertEquals(expected, HaltResolver.followUp(ctx, fields));
    }

    @Test
    void lookupPending_shouldReject() {
        Map<String, Object> notes = new HashMap<>(Map.of(NodeNoteKey.GUARD_LOOKUP.wire(), DENIED_TOOL));
        ToolExecutionContext ctx = context(notes, NONE, contract());

        ToolRejectedException ex = assertThrows(ToolRejectedException.class, () -> HaltResolver.requireNoLookup(ctx));
        assertEquals(RuntimeErrorCode.LOOKUP_PENDING, ex.error());
        assertDoesNotThrow(() -> HaltResolver.requireNoDecision(ctx));
    }

    @Test
    void decisionPending_shouldReject() {
        Map<String, Object> notes = new HashMap<>(Map.of(NodeNoteKey.GUARD_DENIAL.wire(), DENIED_TOOL));
        ToolExecutionContext ctx = context(notes, NONE, contract());

        ToolRejectedException ex = assertThrows(ToolRejectedException.class, () -> HaltResolver.requireNoDecision(ctx));
        assertEquals(RuntimeErrorCode.DECISION_PENDING, ex.error());
        assertDoesNotThrow(() -> HaltResolver.requireNoLookup(ctx));
    }

    @Test
    void nothingPending_shouldPass() {
        ToolExecutionContext ctx = context(new HashMap<>(), NONE, contract());

        assertDoesNotThrow(() -> HaltResolver.requireNoLookup(ctx));
        assertDoesNotThrow(() -> HaltResolver.requireNoDecision(ctx));
    }

    /** T6：最近一次调用 B 多候选；之后旧证据 A 改为 CANCELLED，仍按 B 判 AMBIGUOUS。 */
    @Test
    void t6_followUp_usesLatestEvidenceAfterInPlaceReplace() {
        EvidenceHead a = new EvidenceHead("A", "list", SideEffect.NONE, EvidenceStatus.SUCCESS, true, false,
            Map.of("id", 1), FailureKind.NONE, null, null, null, null, null, null, null, 1, "trace-1", null);
        EvidenceHead b = new EvidenceHead("B", "list", SideEffect.NONE, EvidenceStatus.SUCCESS, true, false,
            Map.of("id", 3), FailureKind.NONE, null, null, null, null, null, null, null, 1, "trace-1", null);
        com.g2rain.agent.runtime.engine.state.DefaultNodeScope scope = liveScope();
        scope.addEvidence(a);
        scope.addEvidence(b);
        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        when(ctx.scope()).thenReturn(scope);
        when(ctx.contract()).thenReturn(contract());

        assertEquals(HaltReason.AMBIGUOUS, HaltResolver.followUp(ctx, List.of()));

        scope.addEvidence(a.withStatus(EvidenceStatus.CANCELLED));
        assertEquals(HaltReason.AMBIGUOUS, HaltResolver.followUp(ctx, List.of()));
        assertEquals("B", scope.evidence().getLast().evidenceId());
        assertEquals(EvidenceStatus.CANCELLED, scope.evidence().getFirst().status());
    }

    private static com.g2rain.agent.runtime.engine.state.DefaultNodeScope liveScope() {
        var fence = new com.g2rain.agent.runtime.domain.state.ConversationFence("c1", "u1", null, 0L, "t", 1L);
        var turn = TurnScopes.create("1", "u1", "t", fence, List.of(),
            List.of(), new HashMap<>(), null, new com.g2rain.agent.runtime.domain.graph.TaskGraph(), null,
            new HashMap<>(), null, new HashMap<>(), 60);
        return new com.g2rain.agent.runtime.engine.state.DefaultNodeScope(turn, "n1", Map.of(), Map.of(), Map.of(),
            new java.util.ArrayList<>(), new java.util.ArrayList<>(), 0, 0L);
    }

    /**
     * 只读写便签与证据的节点作用域。
     *
     * @param notes    便签存储
     * @param evidence 本节点证据
     * @return 节点作用域
     */
    static NodeScope scope(Map<String, Object> notes, List<EvidenceHead> evidence) {
        NodeScope scope = mock(NodeScope.class, CALLS_REAL_METHODS);
        doReturn(notes).when(scope).state();
        doReturn(evidence).when(scope).evidence();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Object value = invocation.getArgument(1);
            if (Objects.isNull(value)) {
                notes.remove(key);
            } else {
                notes.put(key, value);
            }
            return null;
        }).when(scope).note(anyString(), any());
        return scope;
    }

    private static ToolExecutionContext context(Map<String, Object> notes, List<EvidenceHead> evidence,
                                                @Nullable SkillContract contract) {
        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        NodeScope scope = scope(notes, evidence);
        when(ctx.scope()).thenReturn(scope);
        when(ctx.contract()).thenReturn(contract);
        return ctx;
    }

    private static SkillContract contract() {
        ArgSpec title = new ArgSpec();
        title.setSources(List.of(SourceType.USER));
        ArgSpec id = new ArgSpec();
        id.setSources(List.of(SourceType.EVIDENCE));
        ToolSpec save = new ToolSpec();
        save.setArgs(Map.of("title", title, "id", id));
        SkillContract contract = new SkillContract();
        contract.setTools(Map.of("save", save));
        return contract;
    }

    private static EvidenceHead head(EvidenceStatus status, @Nullable Boolean businessOk, @Nullable Boolean empty,
                                     @Nullable Map<String, Integer> extractCounts) {
        return new EvidenceHead("e1", "list", SideEffect.NONE, status, businessOk, empty, extractCounts,
            FailureKind.NONE, null, null, null, null, null, null, null, 1, "trace-1", null);
    }
}
