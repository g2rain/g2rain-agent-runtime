package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.react.ChatRole;
import com.g2rain.agent.runtime.domain.react.ChatTurn;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.state.repo.CallRepository;
import com.g2rain.agent.runtime.engine.state.repo.ConversationRepository;
import com.g2rain.agent.runtime.engine.state.repo.PayloadRepository;
import com.g2rain.agent.runtime.engine.store.StateStore;
import com.g2rain.agent.runtime.spec.ArgSpec;
import com.g2rain.agent.runtime.spec.SideEffect;
import com.g2rain.agent.runtime.spec.ToolSpec;
import com.g2rain.agent.runtime.support.ArgsBrief;
import com.g2rain.agent.runtime.support.PlatformCatalog;
import com.g2rain.common.json.JsonCodecFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultRuntimeStateFacade#history} 用例 A1～A3、A5。
 *
 * @author alpha
 * @since 2026/11/22
 */
class DefaultRuntimeStateFacadeHistoryTest {

    private static final String TASK = "task-1";
    private static final String TOOL = "save";

    private CallRepository calls;

    private DefaultRuntimeStateFacade facade;

    @BeforeEach
    void setUp() {
        calls = mock(CallRepository.class);
        facade = new DefaultRuntimeStateFacade(mock(TurnSessionHydrate.class), mock(TurnFlushPlan.class),
            mock(EvidenceFlow.class), mock(StateStore.class), mock(RuntimeStatePersistence.class),
            mock(ConversationRepository.class), calls, mock(PayloadRepository.class), mock(EntityExtractor.class),
            mock(PlatformCatalog.class), new RuntimeProperties());
    }

    /** A1：长字段完整，敏感字段为 ***。 */
    @Test
    void a1_history_masksSensitiveKeepsLongField() {
        String body = "x".repeat(5000);
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("body", body);
        request.put("token", "secret");
        when(calls.requests(eq(TASK), any())).thenReturn(Map.of("e1", JsonCodecFactory.instance().obj2str(request)));

        DefaultNodeScope node = nodeWith(List.of(head("e1", "brief")));
        List<ChatTurn> history = facade.history(node, List.of(), contract(true));

        JsonNode args = argsOf(history, "e1");
        assertThat(args.get("body").asString()).isEqualTo(body);
        assertThat(args.get("token").asString()).isEqualTo(ArgsBrief.MASK);
    }

    /** A2：查不到 request → 退回 argsBrief。 */
    @Test
    void a2_history_missingRequest_fallsBackToArgsBrief() {
        when(calls.requests(eq(TASK), any())).thenReturn(Map.of());
        DefaultNodeScope node = nodeWith(List.of(head("e1", "{\"fallback\":true}")));

        List<ChatTurn> history = facade.history(node, List.of(), contract(false));

        assertThat(argsOf(history, "e1").get("fallback").asBoolean()).isTrue();
    }

    /** A3：3 条证据时 argsOf/requests 只调一次，含全部 id。 */
    @Test
    @SuppressWarnings("unchecked")
    void a3_history_argsOfCalledOnceWithAllIds() {
        when(calls.requests(eq(TASK), any())).thenReturn(Map.of(
            "e1", "{}", "e2", "{}", "e3", "{}"));
        DefaultNodeScope node = nodeWith(List.of(head("e1", "{}"), head("e2", "{}"), head("e3", "{}")));

        facade.history(node, List.of(), null);

        ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(calls, times(1)).requests(eq(TASK), ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder("e1", "e2", "e3");
    }

    /** A5：契约为 null → 不打码，参数为完整入参。 */
    @Test
    void a5_history_nullContract_keepsPlainArgs() {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("token", "secret");
        when(calls.requests(eq(TASK), any())).thenReturn(Map.of("e1", JsonCodecFactory.instance().obj2str(request)));
        DefaultNodeScope node = nodeWith(List.of(head("e1", "{}")));

        List<ChatTurn> history = facade.history(node, List.of(), null);

        assertThat(argsOf(history, "e1").get("token").asString()).isEqualTo("secret");
    }

    private static JsonNode argsOf(List<ChatTurn> history, String evidenceId) {
        ChatTurn.ToolCallPart part = history.stream()
            .filter(turn -> turn.role() == ChatRole.ASSISTANT)
            .flatMap(turn -> turn.toolCalls().stream())
            .filter(call -> evidenceId.equals(call.id()))
            .findFirst()
            .orElseThrow();
        return JsonCodecFactory.instance().str2node(part.arguments());
    }

    private static DefaultNodeScope nodeWith(List<EvidenceHead> evidence) {
        ConversationFence fence = new ConversationFence("c1", "u1", TASK, 0L, "trace", System.currentTimeMillis());
        DefaultTurnScope turn = new DefaultTurnScope("1", "u1", "trace", fence, List.of(), List.of(), new HashMap<>(),
            null, new TaskGraph(), null, new HashMap<>(), null, new HashMap<>(), 60);
        TaskMetadata meta = new TaskMetadata();
        meta.setTaskId(TASK);
        meta.setConversationId("c1");
        meta.setStatus(TaskStatus.RUNNING);
        turn.start(meta, new Prior(List.of(), Map.of(), List.of()));
        DefaultNodeScope node = new DefaultNodeScope(turn, "n1", Map.of(), Map.of(), Map.of(), new ArrayList<>(evidence),
            new ArrayList<>(), 1, 0L);
        return node;
    }

    private static EvidenceHead head(String id, String argsBrief) {
        return new EvidenceHead(id, TOOL, SideEffect.WRITE, EvidenceStatus.SUCCESS, true, false, null,
            FailureKind.NONE, null, null, argsBrief, "ok", null, null, null, 1, "trace", null);
    }

    private static SkillContract contract(boolean sensitiveToken) {
        ArgSpec token = new ArgSpec();
        token.setSensitive(sensitiveToken);
        ToolSpec tool = new ToolSpec();
        tool.setArgs(Map.of("token", token));
        SkillContract contract = new SkillContract();
        contract.setTools(Map.of(TOOL, tool));
        return contract;
    }
}
