package com.g2rain.agent.runtime.engine.tools;

import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.progress.StepStage;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.event.StepEvents;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import com.g2rain.agent.runtime.spi.Capability;
import com.g2rain.agent.runtime.spi.CapabilityCall;
import com.g2rain.agent.runtime.spi.CapabilityResult;
import com.g2rain.agent.runtime.spi.CapabilitySpec;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.guard.EvidenceReader;
import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCallback;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCategory;
import com.g2rain.agent.runtime.support.tool.CapabilityToolCallback;
import com.g2rain.common.json.JsonCodecFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ToolEvents}：内置工具一律推送 TOOL 步骤，标题取自注解文案。
 *
 * @author alpha
 * @since 2026/11/23
 */
@ExtendWith(MockitoExtension.class)
class ToolEventsTest {

    @Mock
    private StepEvents stepEvents;

    private ToolEvents toolEvents;

    @BeforeEach
    void setUp() {
        toolEvents = new ToolEvents(stepEvents);
        lenient().when(stepEvents.start(any(), anyString(), any(), any())).thenReturn("step-1");
    }

    @Test
    void configuredBuiltinToolsPushAnnotatedTitles() {
        assertTitle(BuiltinToolAction.ASK, RuntimeMessage.STEP_ASK);
        assertTitle(BuiltinToolAction.APPROVAL, RuntimeMessage.STEP_APPROVAL);
        assertTitle(BuiltinToolAction.FINISH, RuntimeMessage.STEP_FINISH);
        assertTitle(BuiltinToolAction.SKIP, RuntimeMessage.STEP_SKIP);
        assertTitle(BuiltinToolAction.FAIL, RuntimeMessage.STEP_FAIL);
        assertTitle(BuiltinToolAction.OUTPUT, RuntimeMessage.STEP_OUTPUT);
        assertTitle(BuiltinToolAction.WRITE, RuntimeMessage.STEP_STATE);
        assertTitle(BuiltinToolAction.READ, RuntimeMessage.STEP_EVIDENCE);
        assertTitle(BuiltinToolAction.READ, RuntimeMessage.STEP_SESSION);
    }

    @Test
    void unconfiguredBuiltinUsesDefaultTitle() {
        assertTitle(BuiltinToolAction.QUERY, RuntimeMessage.STEP_TOOL_DEFAULT);
    }

    @Test
    void delegateTitleIncludesSkill() {
        ToolCallback delegate = callback("builtin_node_delegate");
        BuiltinToolCallback builtin = new BuiltinToolCallback(delegate, BuiltinToolCategory.CONTROL,
            BuiltinToolAction.DELEGATE, RuntimeMessage.STEP_DELEGATE);
        ToolExecutionContext context = mock(ToolExecutionContext.class);
        NodeScope scope = mock(NodeScope.class);
        when(context.getNodeId()).thenReturn("n1");
        when(context.scope()).thenReturn(scope);
        when(scope.rounds()).thenReturn(2);
        Skill skill = mock(Skill.class);
        when(skill.skillKey()).thenReturn("cms.category");
        when(skill.title()).thenReturn("分类管理");
        when(context.getSkills()).thenReturn(List.of(skill));

        ToolInvokeRequest request = mock(ToolInvokeRequest.class);
        when(request.toolCallback()).thenReturn(builtin);
        when(request.context()).thenReturn(context);
        ObjectNode args = JsonCodecFactory.instance().str2obj("{\"skillKey\":\"cms.category\"}", ObjectNode.class);
        when(request.args()).thenReturn(args);

        String stepId = toolEvents.started(request);
        assertNotNull(stepId);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(stepEvents).start(eq(StepStage.TOOL), title.capture(), eq("n1"), eq(2));
        assertEquals(RuntimeMessage.STEP_DELEGATE.text("skill", "分类管理"), title.getValue());
    }

    @Test
    void capabilityTitleUsesLabel() {
        Capability capability = new Capability() {
            @Override
            public CapabilitySpec spec() {
                return new CapabilitySpec("builtin_sample_lookup", "示例查询", "desc",
                    JsonNodeFactory.instance.objectNode(), false);
            }

            @Override
            public CapabilityResult call(CapabilityCall call) {
                return CapabilityResult.success("{}");
            }
        };
        CapabilityToolCallback delegate = new CapabilityToolCallback(capability, mock(EvidenceReader.class),
            mock(RuntimeStateFacade.class));
        BuiltinToolCallback builtin = new BuiltinToolCallback(
            delegate, BuiltinToolCategory.CAPABILITY, BuiltinToolAction.CAPABILITY, RuntimeMessage.STEP_TOOL);

        ToolExecutionContext context = mock(ToolExecutionContext.class);
        NodeScope scope = mock(NodeScope.class);
        when(context.getNodeId()).thenReturn("n1");
        when(context.scope()).thenReturn(scope);
        when(scope.rounds()).thenReturn(1);

        ToolInvokeRequest request = mock(ToolInvokeRequest.class);
        when(request.toolCallback()).thenReturn(builtin);
        when(request.context()).thenReturn(context);

        assertNotNull(toolEvents.started(request));
        verify(stepEvents).start(eq(StepStage.TOOL), eq(RuntimeMessage.STEP_TOOL.text("name", "示例查询")), eq("n1"),
            eq(1));
    }

    @Test
    void capabilityFinishedMarksSuccessAndFailure() {
        ToolCallback delegate = callback("builtin_sample_lookup");
        BuiltinToolCallback builtin = new BuiltinToolCallback(
            delegate, BuiltinToolCategory.CAPABILITY, BuiltinToolAction.CAPABILITY, RuntimeMessage.STEP_TOOL);
        ToolInvokeRequest request = mock(ToolInvokeRequest.class);
        when(request.toolCallback()).thenReturn(builtin);

        toolEvents.finished("step-ok", request, Observation.success("done", null), null);
        verify(stepEvents).done(eq("step-ok"), eq(RuntimeMessage.STEP_TOOL_OK.text()), any());

        Observation failed = Observation.failed(RuntimeErrorCode.TOOL_ERROR, "条件不满足", "换个条件再试");
        toolEvents.finished("step-fail", request, failed, null);
        verify(stepEvents).fail(eq("step-fail"), eq("条件不满足"), eq(RuntimeErrorCode.TOOL_ERROR.code()), any());
    }

    /**
     * 断言一次内置调用推送的标题等于文案。
     *
     * @param action 动作
     * @param step   步骤文案
     */
    private void assertTitle(BuiltinToolAction action, RuntimeMessage step) {
        ToolCallback delegate = callback("tool-" + step.name());
        BuiltinToolCallback builtin = new BuiltinToolCallback(delegate, BuiltinToolCategory.CONTROL, action, step);
        ToolExecutionContext context = mock(ToolExecutionContext.class);
        NodeScope scope = mock(NodeScope.class);
        when(context.getNodeId()).thenReturn("n1");
        when(context.scope()).thenReturn(scope);
        when(scope.rounds()).thenReturn(1);

        ToolInvokeRequest request = mock(ToolInvokeRequest.class);
        when(request.toolCallback()).thenReturn(builtin);
        when(request.context()).thenReturn(context);

        assertNotNull(toolEvents.started(request));
        verify(stepEvents).start(eq(StepStage.TOOL), eq(step.text()), eq("n1"), eq(1));
    }

    /**
     * 假回调；本测试只装饰为内置工具，不读定义。
     *
     * @param name 工具名（仅作区分）
     * @return 回调
     */
    private static ToolCallback callback(String name) {
        return mock(ToolCallback.class, name);
    }
}
