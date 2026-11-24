package com.g2rain.agent.runtime.engine.tools.guard;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.Outcome;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.ToolInterceptor;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.spec.SideEffect;
import com.g2rain.agent.runtime.spec.SourceType;
import com.g2rain.agent.runtime.spi.Call;
import com.g2rain.agent.runtime.spi.CallView;
import com.g2rain.agent.runtime.spi.Denial;
import com.g2rain.agent.runtime.spi.Fact;
import com.g2rain.agent.runtime.spi.GuardVerdict;
import com.g2rain.agent.runtime.spi.Notice;
import com.g2rain.agent.runtime.spi.ToolGuard;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCallback;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCategory;
import com.g2rain.common.json.JsonCodecFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GuardInterceptor} 放行、拒绝、拦截便签与顺序验收。
 *
 * @author alpha
 * @since 2026/11/17
 */
class GuardInterceptorTest {

    @Test
    void noGuards_shouldPassWithoutReadingEvidence() {
        EvidenceReader evidenceReader = mock(EvidenceReader.class);
        GuardInterceptor interceptor = new GuardInterceptor(List.of(), evidenceReader);
        ToolInvokeRequest request = mock(ToolInvokeRequest.class);

        assertTrue(interceptor.before(request).isEmpty());
        verify(evidenceReader, never()).nodeCalls(any());
    }

    @Test
    void builtinTool_shouldPassWithoutCallingGuard() {
        ToolGuard guard = mock(ToolGuard.class);
        EvidenceReader evidenceReader = mock(EvidenceReader.class);
        GuardInterceptor interceptor = new GuardInterceptor(List.of(guard), evidenceReader);

        ToolCallback delegate = mock(ToolCallback.class);
        ToolInvokeRequest request = mock(ToolInvokeRequest.class);
        when(request.toolCallback()).thenReturn(new BuiltinToolCallback(delegate, BuiltinToolCategory.CONTROL,
            BuiltinToolAction.FINISH, RuntimeMessage.STEP_FINISH));

        assertTrue(interceptor.before(request).isEmpty());
        verify(guard, never()).check(any());
        verify(evidenceReader, never()).nodeCalls(any());
    }

    @Test
    void approvedWrite_shouldPassWithoutCallingGuard() {
        ToolGuard guard = mock(ToolGuard.class);
        EvidenceReader evidenceReader = mock(EvidenceReader.class);
        GuardInterceptor interceptor = new GuardInterceptor(List.of(guard), evidenceReader);

        ToolExecutionContext context = mock(ToolExecutionContext.class);
        when(context.getSkillKey()).thenReturn("cms.article");
        when(context.approvedWrite()).thenReturn(true);

        ToolInvokeRequest request = businessRequest(context);
        assertTrue(interceptor.before(request).isEmpty());
        verify(guard, never()).check(any());
    }

    @Test
    void guardDenial_shouldReturnRejectedWithDenialText() {
        ToolGuard guard = mock(ToolGuard.class);
        when(guard.check(any())).thenReturn(GuardVerdict.deny(Denial.refuse("SHAPE_FAIL", "前置不满足", "先补齐事实")));
        GuardInterceptor interceptor = new GuardInterceptor(List.of(guard), emptyEvidence());

        Observation observation = interceptor.before(businessRequest(readyContext())).orElseThrow();
        assertEquals(Outcome.REJECTED, observation.outcome());
        assertEquals(RuntimeErrorCode.GUARD_DENIED, observation.errorCode());
        assertEquals("SHAPE_FAIL", observation.reasonCode());
        assertEquals("前置不满足", observation.text());
        assertEquals("先补齐事实", observation.next());
    }

    @Test
    void allowWithNotices_shouldRecordNoticesOnRequest() {
        ToolGuard first = mock(ToolGuard.class);
        ToolGuard second = mock(ToolGuard.class);
        Notice notice = new Notice("published-edit", "已发布内容的修改会直接影响线上");
        when(first.check(any())).thenReturn(GuardVerdict.allow(List.of(notice)));
        when(second.check(any())).thenReturn(GuardVerdict.allow());
        GuardInterceptor interceptor = new GuardInterceptor(List.of(first, second), emptyEvidence());
        ToolInvokeRequest request = businessRequest(readyContext());

        assertTrue(interceptor.before(request).isEmpty());
        verify(request).notices(List.of(notice));
    }

    @Test
    void firstDenial_shouldSkipLaterGuards() {
        ToolGuard first = mock(ToolGuard.class);
        ToolGuard second = mock(ToolGuard.class);
        when(first.check(any())).thenReturn(GuardVerdict.deny(Denial.refuse("A", "第一拒绝", "停")));
        GuardInterceptor interceptor = new GuardInterceptor(List.of(first, second), emptyEvidence());

        Observation observation = interceptor.before(businessRequest(readyContext())).orElseThrow();
        assertEquals("第一拒绝", observation.text());
        verify(second, never()).check(any());
    }

    @Test
    void secondDenial_shouldReturnAfterFirstPass() {
        ToolGuard first = mock(ToolGuard.class);
        ToolGuard second = mock(ToolGuard.class);
        when(first.check(any())).thenReturn(GuardVerdict.allow());
        when(second.check(any())).thenReturn(GuardVerdict.deny(Denial.refuse("B", "第二拒绝", "改参数")));
        GuardInterceptor interceptor = new GuardInterceptor(List.of(first, second), emptyEvidence());

        Observation observation = interceptor.before(businessRequest(readyContext())).orElseThrow();
        assertEquals("第二拒绝", observation.text());
        verify(first).check(any());
        verify(second).check(any());
    }

    @Test
    void guardException_onWriteTool_shouldReturnUnavailable() {
        assertUnavailable();
    }

    @Test
    void guardException_onReadTool_shouldReturnUnavailable() {
        assertUnavailable();
    }

    @Test
    void callView_shouldExposeCopySourcesCallsAndPin() {
        ToolGuard guard = mock(ToolGuard.class);
        when(guard.check(any())).thenReturn(GuardVerdict.allow());

        Call local = new Call("local_tool", JsonNodeFactory.instance.objectNode().put("q", 1), SideEffect.NONE,
            List.of(new Fact("localKey", JsonCodecFactory.instance().str2node("\"L\""))));
        Call upstream = new Call("up_tool", JsonNodeFactory.instance.objectNode(), SideEffect.WRITE,
            List.of(new Fact("upKey", JsonCodecFactory.instance().str2node("\"U\""))));
        EvidenceReader evidenceReader = mock(EvidenceReader.class);
        when(evidenceReader.nodeCalls(any())).thenReturn(List.of(local, upstream));

        ToolExecutionContext context = readyContext();
        when(context.getSkill()).thenReturn(new Skill("cms.article", null, List.of("save"), null, null, null, "pin-9"));

        ObjectNode args = (ObjectNode) JsonCodecFactory.instance().str2node("{\"id\":1}");
        Map<String, Source> argSources = new LinkedHashMap<>();
        argSources.put("id", new Source(SourceType.EVIDENCE, "e1", "$.id"));

        ToolInvokeRequest request = businessRequest(context);
        when(request.args()).thenReturn(args);
        when(request.argSources()).thenReturn(argSources);
        when(request.toolName()).thenReturn("save");
        when(request.callId()).thenReturn("call-1");

        GuardInterceptor interceptor = new GuardInterceptor(List.of(guard), evidenceReader);
        assertTrue(interceptor.before(request).isEmpty());

        ArgumentCaptor<CallView> captor = ArgumentCaptor.forClass(CallView.class);
        verify(guard).check(captor.capture());
        CallView view = captor.getValue();
        assertEquals("cms.article", view.skillKey());
        assertEquals("save", view.toolName());
        assertEquals("pin-9", view.pin());
        assertEquals("call-1", view.callId());
        assertEquals(SourceType.EVIDENCE, view.sources().get("id"));
        assertEquals(List.of(local, upstream), view.calls());

        assertInstanceOf(ObjectNode.class, view.arguments());
        ((ObjectNode) view.arguments()).put("id", 99);
        assertEquals(1, args.get("id").asInt());
    }

    @Test
    void refuse_shouldNoteDenialAndClearLookup() {
        ToolGuard guard = mock(ToolGuard.class);
        when(guard.check(any())).thenReturn(GuardVerdict.deny(Denial.refuse("ENABLED", "启用中不能删除", "说明原因")));
        Map<String, Object> notes = new HashMap<>(Map.of(NodeNoteKey.GUARD_LOOKUP.wire(), "save"));
        GuardInterceptor interceptor = new GuardInterceptor(List.of(guard), emptyEvidence());

        interceptor.before(businessRequest(readyContext(notes)));

        assertEquals(Map.of(NodeNoteKey.GUARD_DENIAL.wire(), "save"), notes);
    }

    @Test
    void lookup_shouldNoteLookup() {
        ToolGuard guard = mock(ToolGuard.class);
        when(guard.check(any())).thenReturn(GuardVerdict.deny(Denial.lookup("FACT_MISSING", "缺少事实", "先查询")));
        Map<String, Object> notes = new HashMap<>();
        GuardInterceptor interceptor = new GuardInterceptor(List.of(guard), emptyEvidence());

        Observation observation = interceptor.before(businessRequest(readyContext(notes))).orElseThrow();

        assertEquals(RuntimeErrorCode.GUARD_LOOKUP, observation.errorCode());
        assertEquals(Map.of(NodeNoteKey.GUARD_LOOKUP.wire(), "save"), notes);
    }

    @Test
    void blockedToolPasses_shouldReleaseItsNotes() {
        ToolGuard guard = mock(ToolGuard.class);
        when(guard.check(any())).thenReturn(GuardVerdict.allow());
        Map<String, Object> notes = new HashMap<>(Map.of(
            NodeNoteKey.GUARD_DENIAL.wire(), "save", NodeNoteKey.GUARD_LOOKUP.wire(), "save"));
        GuardInterceptor interceptor = new GuardInterceptor(List.of(guard), emptyEvidence());

        assertTrue(interceptor.before(businessRequest(readyContext(notes))).isEmpty());
        assertTrue(notes.isEmpty());
    }

    @Test
    void otherToolPasses_shouldKeepNotes() {
        ToolGuard guard = mock(ToolGuard.class);
        when(guard.check(any())).thenReturn(GuardVerdict.allow());
        Map<String, Object> notes = new HashMap<>(Map.of(NodeNoteKey.GUARD_DENIAL.wire(), "delete"));
        GuardInterceptor interceptor = new GuardInterceptor(List.of(guard), emptyEvidence());

        assertTrue(interceptor.before(businessRequest(readyContext(notes))).isEmpty());
        assertEquals(Map.of(NodeNoteKey.GUARD_DENIAL.wire(), "delete"), notes);
    }

    @Test
    void interceptorOrder_shouldMatchContractChain() {
        RuntimeProperties properties = mock(RuntimeProperties.class);
        RuntimeStateFacade stateFacade = mock(RuntimeStateFacade.class);
        EvidenceReader evidenceReader = mock(EvidenceReader.class);
        SourceResolver sourceResolver = mock(SourceResolver.class);

        List<ToolInterceptor> interceptors = new ArrayList<>(List.of(
            new WriteScopeInterceptor(),
            new ArgPolicyInterceptor(),
            new ArgBindingInterceptor(evidenceReader),
            new ParamContractInterceptor(sourceResolver),
            new GuardInterceptor(List.of(), evidenceReader),
            new FingerprintInterceptor(properties),
            new WriteGateInterceptor(stateFacade, properties),
            new EvidenceInterceptor(stateFacade),
            new ResponseContractInterceptor(properties)
        ));
        interceptors.sort(AnnotationAwareOrderComparator.INSTANCE);

        assertEquals(List.of(
            WriteScopeInterceptor.class,
            ArgPolicyInterceptor.class,
            ArgBindingInterceptor.class,
            ParamContractInterceptor.class,
            GuardInterceptor.class,
            FingerprintInterceptor.class,
            WriteGateInterceptor.class,
            EvidenceInterceptor.class,
            ResponseContractInterceptor.class
        ), interceptors.stream().map(Object::getClass).toList());
    }

    private static void assertUnavailable() {
        ToolGuard guard = mock(ToolGuard.class);
        when(guard.check(any())).thenThrow(new IllegalStateException("boom"));
        GuardInterceptor interceptor = new GuardInterceptor(List.of(guard), emptyEvidence());

        Observation observation = interceptor.before(businessRequest(readyContext())).orElseThrow();
        assertEquals(Outcome.REJECTED, observation.outcome());
        assertEquals(RuntimeErrorCode.GUARD_UNAVAILABLE, observation.errorCode());
    }

    private static EvidenceReader emptyEvidence() {
        EvidenceReader evidenceReader = mock(EvidenceReader.class);
        when(evidenceReader.nodeCalls(any())).thenReturn(List.of());
        return evidenceReader;
    }

    private static ToolExecutionContext readyContext() {
        return readyContext(new HashMap<>());
    }

    private static ToolExecutionContext readyContext(Map<String, Object> notes) {
        NodeScope scope = mock(NodeScope.class, CALLS_REAL_METHODS);
        doReturn(notes).when(scope).state();
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

        ToolExecutionContext context = mock(ToolExecutionContext.class);
        when(context.scope()).thenReturn(scope);
        when(context.getSkillKey()).thenReturn("cms.article");
        when(context.approvedWrite()).thenReturn(false);
        when(context.getSkill()).thenReturn(new Skill("cms.article"));
        return context;
    }

    private static ToolInvokeRequest businessRequest(ToolExecutionContext context) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolInvokeRequest request = mock(ToolInvokeRequest.class);
        when(request.context()).thenReturn(context);
        when(request.toolCallback()).thenReturn(callback);
        when(request.toolName()).thenReturn("save");
        when(request.callId()).thenReturn("call-test");
        when(request.args()).thenReturn(JsonNodeFactory.instance.objectNode());
        when(request.argSources()).thenReturn(Map.of());
        return request;
    }
}
