package com.g2rain.agent.runtime.engine.flow.finalize;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.engine.model.ModelGateway;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.event.StepEvents;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.model.nexus.dto.ChatCompletionRequest;
import com.g2rain.model.nexus.enums.MessageRole;
import com.g2rain.model.nexus.schema.ChatCompletionChoice;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.vo.ChatCompletionResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultGoalGate} 节点结论截断用例 P4、P5。
 *
 * @author alpha
 * @since 2026/11/22
 */
class DefaultGoalGateTruncationTest {

    /** P4：result 超过上限 → truncated 为 true，result 被截短。 */
    @Test
    void p4_resultOverLimit_setsTruncated() {
        JsonNode node = captureNodeView("x".repeat(20), 8);
        assertThat(node.get("truncated").asBoolean()).isTrue();
        assertThat(node.get("result").asString()).hasSize(8);
    }

    /** P5：result 未超过上限 → 序列化没有 truncated。 */
    @Test
    void p5_resultWithinLimit_omitsTruncated() {
        JsonNode node = captureNodeView("short", 500);
        assertThat(node.get("truncated")).isNull();
        assertThat(node.get("result").asString()).isEqualTo("short");
    }

    private static JsonNode captureNodeView(String result, int maxChars) {
        RuntimeProperties properties = new RuntimeProperties();
        properties.getGoalGate().setEnabled(true);
        properties.getGoalGate().setPromptMaxChars(maxChars);

        ModelGateway gateway = mock(ModelGateway.class);
        when(gateway.chat(any(), any(), any())).thenReturn(gateResponse());
        StepEvents stepEvents = mock(StepEvents.class);
        when(stepEvents.start(any(), any())).thenReturn("step");
        RuntimeStateFacade stateFacade = mock(RuntimeStateFacade.class);
        when(stateFacade.evidenceOf(any(), any())).thenReturn(List.of());

        DefaultGoalGate gate = new DefaultGoalGate(properties, gateway, stepEvents, stateFacade);

        TaskNode a = node("a", result);
        TaskNode b = node("b", "other");
        TaskGraph graph = new TaskGraph();
        graph.setNodes(List.of(a, b));
        TaskMetadata meta = new TaskMetadata();
        meta.setGoalSummary("goal");
        TurnScope turn = mock(TurnScope.class);
        when(turn.task()).thenReturn(Optional.of(meta));
        when(turn.graph()).thenReturn(graph);
        when(turn.dialogue(org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

        TurnRequest request = new TurnRequest();
        TurnContext ctx = new TurnContext(request, turn);
        gate.present(ctx, null, true);

        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(gateway).chat(any(), any(), captor.capture());
        String user = captor.getValue().getMessages().get(1).getContent();
        int from = user.indexOf("【输入数据】") + "【输入数据】".length();
        int to = user.indexOf("【输出】");
        return JsonCodecFactory.instance().str2node(user.substring(from, to).trim()).get("nodes").get(0);
    }

    private static TaskNode node(String id, String result) {
        TaskNode node = new TaskNode();
        node.setNodeId(id);
        node.setTitle(id);
        node.setStatus(NodeStatus.DONE);
        node.setResult(result);
        return node;
    }

    private static ChatCompletionResponse gateResponse() {
        ChatMessage message = new ChatMessage();
        message.setRole(MessageRole.ASSISTANT);
        message.setContent("{\"achieved\":true,\"finalMessage\":\"ok\"}");
        ChatCompletionChoice choice = new ChatCompletionChoice();
        choice.setMessage(message);
        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setChoices(List.of(choice));
        return response;
    }
}
