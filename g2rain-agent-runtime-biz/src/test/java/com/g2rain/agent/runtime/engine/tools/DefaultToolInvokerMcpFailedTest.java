package com.g2rain.agent.runtime.engine.tools;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.Outcome;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.engine.state.repo.BlockRepository;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultToolInvoker} MCP 失败文案用例 Q3。
 *
 * @author alpha
 * @since 2026/11/22
 */
class DefaultToolInvokerMcpFailedTest {

    /** Q3：MCP 返回错误文本 → 观测原因为「工具执行出错，工具返回：「…」」。 */
    @Test
    void q3_mcpToolError_usesFencedMessage() {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("search");
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(any(), any())).thenThrow(new BusinessException(RuntimeErrorCode.MCP_TOOL_ERROR, "上游 boom"));

        ToolExecutionContext context = mock(ToolExecutionContext.class);
        NodeScope scope = mock(NodeScope.class);
        TurnScope turn = mock(TurnScope.class);
        when(context.claimTarget(any())).thenReturn(false);
        when(context.getTaskId()).thenReturn("task-1");
        when(context.getNodeId()).thenReturn("node-1");
        when(context.getSkillKey()).thenReturn("cms.article");
        when(context.getSkill()).thenReturn(null);
        when(context.contract()).thenReturn(null);
        when(context.scope()).thenReturn(scope);
        when(scope.rounds()).thenReturn(1);
        when(scope.turn()).thenReturn(turn);
        when(turn.traceId()).thenReturn("trace-1");

        ToolEvents toolEvents = mock(ToolEvents.class);
        when(toolEvents.started(any())).thenReturn("step");
        DefaultToolInvoker invoker = new DefaultToolInvoker(List.of(), toolEvents, new RuntimeProperties(),
            mock(BlockRepository.class));

        Observation observation = invoker.invoke(new ToolInvokeRequest(context, callback, "{}"));

        assertThat(observation.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(observation.errorCode()).isEqualTo(RuntimeErrorCode.MCP_TOOL_ERROR);
        assertThat(observation.text()).isEqualTo("工具执行出错，工具返回：「上游 boom」");
    }
}
