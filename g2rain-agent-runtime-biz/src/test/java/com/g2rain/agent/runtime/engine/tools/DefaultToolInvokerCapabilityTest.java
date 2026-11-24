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
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCallback;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCategory;
import com.g2rain.agent.runtime.support.tool.CapabilityFailure;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultToolInvoker}：{@link CapabilityFailure} 收成失败观测。
 *
 * @author alpha
 * @since 2026/11/24
 */
class DefaultToolInvokerCapabilityTest {

    @Test
    void capabilityFailureBecomesFailedObservation() {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("sample_lookup");
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(any(), any())).thenThrow(new CapabilityFailure("条件不满足", "换个条件再试"));
        BuiltinToolCallback callback = new BuiltinToolCallback(
            delegate, BuiltinToolCategory.CAPABILITY, BuiltinToolAction.CAPABILITY, RuntimeMessage.STEP_TOOL);

        ToolExecutionContext context = mock(ToolExecutionContext.class);
        NodeScope scope = mock(NodeScope.class);
        TurnScope turn = mock(TurnScope.class);
        when(context.claimTarget(any())).thenReturn(false);
        when(context.getTaskId()).thenReturn("task-1");
        when(context.getNodeId()).thenReturn("node-1");
        when(context.getSkillKey()).thenReturn("demo.skill");
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
        assertThat(observation.errorCode()).isEqualTo(RuntimeErrorCode.TOOL_ERROR);
        assertThat(observation.message()).isEqualTo("条件不满足。下一步：换个条件再试");
    }
}
