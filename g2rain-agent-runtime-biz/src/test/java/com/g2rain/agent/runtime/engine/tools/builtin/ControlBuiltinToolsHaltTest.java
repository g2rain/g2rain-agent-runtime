package com.g2rain.agent.runtime.engine.tools.builtin;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.HaltReason;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.tools.ToolExecutionContexts;
import com.g2rain.agent.runtime.engine.tools.ToolRejectedException;
import com.g2rain.agent.runtime.engine.tools.finish.NodeFinisher;
import com.g2rain.agent.runtime.engine.tools.guard.EvidenceReader;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ControlBuiltinTools} 停顿便签 × 控流工具矩阵验收。
 *
 * @author alpha
 * @since 2026/11/21
 */
class ControlBuiltinToolsHaltTest {

    private static final String BLOCKED_TOOL = "delete";

    private static final String QUESTION = "该分类启用中，如何处理？";

    private static final List<String> DECISION_FIELDS = List.of("处理方式");

    /**
     * 控流动作。
     */
    enum Action {

        FOLLOW_UP,

        FAIL,

        FINISH;

        void invoke(ControlBuiltinTools tools, ToolContext toolContext) {
            switch (this) {
                case FOLLOW_UP -> tools.followUp(QUESTION, DECISION_FIELDS, List.of("先停用再删除"), toolContext);
                case FAIL -> tools.fail("无法删除", toolContext);
                case FINISH -> tools.finish("已处理", toolContext);
            }
        }
    }

    /**
     * 便签 × 控流动作 → 拒绝码（{@code null} 表示放行）。
     */
    static Stream<Arguments> haltMatrix() {
        return Stream.of(
            Arguments.of(NodeNoteKey.GUARD_LOOKUP, Action.FOLLOW_UP, RuntimeErrorCode.LOOKUP_PENDING),
            Arguments.of(NodeNoteKey.GUARD_LOOKUP, Action.FAIL, RuntimeErrorCode.LOOKUP_PENDING),
            Arguments.of(NodeNoteKey.GUARD_LOOKUP, Action.FINISH, RuntimeErrorCode.LOOKUP_PENDING),
            Arguments.of(NodeNoteKey.GUARD_DENIAL, Action.FOLLOW_UP, null),
            Arguments.of(NodeNoteKey.GUARD_DENIAL, Action.FAIL, RuntimeErrorCode.DECISION_PENDING),
            Arguments.of(NodeNoteKey.GUARD_DENIAL, Action.FINISH, null)
        );
    }

    @ParameterizedTest(name = "{0} × {1}")
    @MethodSource("haltMatrix")
    void haltNote(NodeNoteKey note, Action action, @Nullable RuntimeErrorCode expected) {
        Map<String, Object> notes = new HashMap<>(Map.of(note.wire(), BLOCKED_TOOL));
        ToolExecutionContext ctx = context(notes);
        ControlBuiltinTools tools = tools();
        ToolContext toolContext = toolContext(ctx);

        if (Objects.isNull(expected)) {
            assertDoesNotThrow(() -> action.invoke(tools, toolContext));
            return;
        }

        ToolRejectedException ex = assertThrows(ToolRejectedException.class, () -> action.invoke(tools, toolContext));
        assertEquals(expected, ex.error());
    }

    @Test
    void nothingPending_failPasses() {
        ToolExecutionContext ctx = context(new HashMap<>());

        assertDoesNotThrow(() -> Action.FAIL.invoke(tools(), toolContext(ctx)));
        verify(ctx).applyFail("无法删除");
    }

    @Test
    void deniedFollowUp_dropsFieldsAndReleasesDecision() {
        Map<String, Object> notes = new HashMap<>(Map.of(NodeNoteKey.GUARD_DENIAL.wire(), BLOCKED_TOOL));
        ToolExecutionContext ctx = context(notes);

        Action.FOLLOW_UP.invoke(tools(), toolContext(ctx));

        verify(ctx).applyFollowUp(QUESTION, HaltReason.DENIED, List.of(), List.of("先停用再删除"));
        assertFalse(notes.containsKey(NodeNoteKey.GUARD_DENIAL.wire()));
    }

    private static ControlBuiltinTools tools() {
        return new ControlBuiltinTools(mock(NodeFinisher.class), mock(RuntimeProperties.class), mock(EvidenceReader.class));
    }

    private static ToolContext toolContext(ToolExecutionContext ctx) {
        return new ToolContext(Map.of(ToolExecutionContexts.SPRING_ATTR, ctx));
    }

    private static ToolExecutionContext context(Map<String, Object> notes) {
        NodeScope scope = HaltResolverTest.scope(notes, List.of());
        TurnScope turn = mock(TurnScope.class);
        when(turn.resumed()).thenReturn(Optional.empty());
        GraphFrame frame = mock(GraphFrame.class);
        when(frame.turn()).thenReturn(turn);

        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        when(ctx.scope()).thenReturn(scope);
        when(ctx.getFrame()).thenReturn(frame);
        when(ctx.getNodeStatus()).thenReturn(NodeStatus.RUNNING);
        return ctx;
    }
}
