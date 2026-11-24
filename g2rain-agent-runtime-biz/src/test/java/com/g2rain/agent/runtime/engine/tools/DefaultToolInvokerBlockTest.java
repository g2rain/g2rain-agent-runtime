package com.g2rain.agent.runtime.engine.tools;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.Outcome;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.dto.ToolBlockRecordDto;
import com.g2rain.agent.runtime.engine.state.repo.BlockRepository;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCallback;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCategory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultToolInvoker} 拦截记录写入验收。
 *
 * @author alpha
 * @since 2026/11/17
 */
class DefaultToolInvokerBlockTest {

    @Test
    void businessToolBlocked_shouldPersistBlockRecord() {
        BlockRepository blocks = mock(BlockRepository.class);
        ToolInterceptor blocker = new RejectingInterceptor(
            Observation.rejected(RuntimeErrorCode.ARG_REJECTED, "缺参", "补齐", "R1"));
        DefaultToolInvoker invoker = new DefaultToolInvoker(List.of(blocker), mock(ToolEvents.class),
            mock(RuntimeProperties.class), blocks);

        ToolInvokeRequest request = businessRequest();
        Observation obs = invoker.invoke(request);
        assertEquals(Outcome.REJECTED, obs.outcome());

        ArgumentCaptor<ToolBlockRecordDto> captor = ArgumentCaptor.forClass(ToolBlockRecordDto.class);
        verify(blocks).insert(captor.capture());
        ToolBlockRecordDto dto = captor.getValue();
        assertEquals(request.callId(), dto.getCallId());
        assertEquals("save", dto.getToolName());
        assertEquals("RejectingInterceptor", dto.getBlockedBy());
        assertEquals(RuntimeErrorCode.ARG_REJECTED.code(), dto.getErrorCode());
        assertEquals("R1", dto.getReasonCode());
        assertEquals("缺参", dto.getMessage());
        assertEquals("补齐", dto.getNextStep());
        assertEquals("cms.article", dto.getSkillKey());
        assertEquals("trace-1", dto.getTraceId());
        assertEquals(2, dto.getRound());
    }

    @Test
    void builtinToolBlocked_shouldNotPersistBlockRecord() {
        BlockRepository blocks = mock(BlockRepository.class);
        ToolInterceptor blocker = new RejectingInterceptor(
            Observation.rejected(RuntimeErrorCode.ARG_REJECTED, "缺参", "补齐"));
        DefaultToolInvoker invoker = new DefaultToolInvoker(List.of(blocker), mock(ToolEvents.class),
            mock(RuntimeProperties.class), blocks);

        ToolCallback delegate = mock(ToolCallback.class);
        ToolInvokeRequest request = mock(ToolInvokeRequest.class);
        ToolExecutionContext context = mock(ToolExecutionContext.class);
        when(request.context()).thenReturn(context);
        when(request.toolName()).thenReturn("builtin_node_finish");
        when(request.toolCallback()).thenReturn(new BuiltinToolCallback(delegate, BuiltinToolCategory.CONTROL,
            BuiltinToolAction.FINISH, RuntimeMessage.STEP_FINISH));
        when(context.claimTarget(any())).thenReturn(false);

        Observation obs = invoker.invoke(request);
        assertEquals(Outcome.REJECTED, obs.outcome());
        verify(blocks, never()).insert(any());
    }

    @Test
    void blockRepositoryThrows_shouldStillReturnBlockedObservation() {
        BlockRepository blocks = mock(BlockRepository.class);
        doThrow(new IllegalStateException("db down")).when(blocks).insert(any());
        ToolInterceptor blocker = new RejectingInterceptor(
            Observation.rejected(RuntimeErrorCode.GUARD_DENIED, "拦下", "停", "SHAPE"));
        DefaultToolInvoker invoker = new DefaultToolInvoker(List.of(blocker), mock(ToolEvents.class),
            mock(RuntimeProperties.class), blocks);

        Observation obs = invoker.invoke(businessRequest());
        assertEquals(Outcome.REJECTED, obs.outcome());
        assertEquals(RuntimeErrorCode.GUARD_DENIED, obs.errorCode());
        assertEquals("SHAPE", obs.reasonCode());
        assertTrue(obs.message().contains("拦下"));
    }

    private static final class RejectingInterceptor implements ToolInterceptor {
        private final Observation observation;

        private RejectingInterceptor(Observation observation) {
            this.observation = observation;
        }

        @Override
        public Optional<Observation> before(ToolInvokeRequest request) {
            return Optional.of(observation);
        }
    }

    private static ToolInvokeRequest businessRequest() {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("save");
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);

        ToolExecutionContext context = mock(ToolExecutionContext.class);
        NodeScope scope = mock(NodeScope.class);
        TurnScope turn = mock(TurnScope.class);
        when(context.claimTarget(any())).thenReturn(false);
        when(context.getTaskId()).thenReturn("task-1");
        when(context.getNodeId()).thenReturn("node-1");
        when(context.getSkillKey()).thenReturn("cms.article");
        when(context.getSkill()).thenReturn(null);
        when(context.scope()).thenReturn(scope);
        when(scope.rounds()).thenReturn(2);
        when(scope.turn()).thenReturn(turn);
        when(turn.traceId()).thenReturn("trace-1");

        return new ToolInvokeRequest(context, callback, "{}");
    }
}
