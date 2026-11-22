package com.g2rain.agent.runtime.engine.tools.builtin;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.tools.ToolExecutionContexts;
import com.g2rain.agent.runtime.engine.tools.ToolRejectedException;
import com.g2rain.agent.runtime.engine.tools.finish.NodeFinisher;
import com.g2rain.agent.runtime.engine.tools.guard.EvidenceReader;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ControlBuiltinTools#skip} 用例 N10。
 *
 * @author alpha
 * @since 2026/11/22
 */
class ControlBuiltinToolsSkipTest {

    /** N10：有补查待完成 → LOOKUP_PENDING，节点不是 SKIPPED。 */
    @Test
    void n10_skip_lookupPending_rejects() {
        Map<String, Object> notes = new HashMap<>(Map.of(NodeNoteKey.GUARD_LOOKUP.wire(), "lookup_tool"));
        NodeScope scope = HaltResolverTest.scope(notes, List.of());
        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        when(ctx.scope()).thenReturn(scope);
        when(ctx.getNodeStatus()).thenReturn(NodeStatus.RUNNING);
        TurnScope turn = mock(TurnScope.class);
        when(turn.resumed()).thenReturn(Optional.empty());
        GraphFrame frame = mock(GraphFrame.class);
        when(frame.turn()).thenReturn(turn);
        when(ctx.getFrame()).thenReturn(frame);
        ControlBuiltinTools tools = new ControlBuiltinTools(mock(NodeFinisher.class), mock(RuntimeProperties.class),
            mock(EvidenceReader.class));
        ToolContext toolContext = new ToolContext(Map.of(ToolExecutionContexts.SPRING_ATTR, ctx));

        ToolRejectedException ex = assertThrows(ToolRejectedException.class,
            () -> tools.skip("已存在", toolContext));
        assertEquals(RuntimeErrorCode.LOOKUP_PENDING, ex.error());
        verify(ctx, never()).applySkip(any());
        assertEquals(NodeStatus.RUNNING, ctx.getNodeStatus());
    }
}
