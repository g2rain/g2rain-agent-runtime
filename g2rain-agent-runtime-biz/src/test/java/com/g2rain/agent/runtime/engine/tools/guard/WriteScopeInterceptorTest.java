package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.spec.SideEffect;
import com.g2rain.agent.runtime.spec.ToolSpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WriteScopeInterceptor} 拒绝待用户决定期间的写操作验收。
 *
 * @author alpha
 * @since 2026/11/21
 */
class WriteScopeInterceptorTest {

    private static final String DENIED_TOOL = "category_delete";

    private final WriteScopeInterceptor interceptor = new WriteScopeInterceptor();

    @Test
    void otherWriteWhileDecisionPending_shouldReject() {
        Optional<Observation> blocked = interceptor.before(writeRequest("category_disable", DENIED_TOOL));

        assertTrue(blocked.isPresent());
        assertEquals(RuntimeErrorCode.DECISION_PENDING, blocked.get().errorCode());
    }

    @Test
    void deniedToolItself_shouldPass() {
        assertTrue(interceptor.before(writeRequest(DENIED_TOOL, DENIED_TOOL)).isEmpty());
    }

    @Test
    void noDecisionPending_shouldPass() {
        assertTrue(interceptor.before(writeRequest("category_disable", null)).isEmpty());
    }

    private static ToolInvokeRequest writeRequest(String toolName, @Nullable String denied) {
        NodeScope scope = mock(NodeScope.class);
        when(scope.note(NodeNoteKey.GUARD_DENIAL)).thenReturn(denied);
        TaskGraph graph = mock(TaskGraph.class);
        when(graph.targetOwner(any(), any())).thenReturn(null);

        ToolExecutionContext context = mock(ToolExecutionContext.class);
        when(context.scope()).thenReturn(scope);
        when(context.getGraph()).thenReturn(graph);
        when(context.getNodeId()).thenReturn("node-1");
        when(context.getTarget()).thenReturn(DENIED_TOOL);

        ToolSpec spec = new ToolSpec();
        spec.setSideEffect(SideEffect.WRITE);
        ToolInvokeRequest request = mock(ToolInvokeRequest.class);
        when(request.toolCallback()).thenReturn(mock(ToolCallback.class));
        when(request.contract()).thenReturn(spec);
        when(request.context()).thenReturn(context);
        when(request.toolName()).thenReturn(toolName);
        return request;
    }
}
