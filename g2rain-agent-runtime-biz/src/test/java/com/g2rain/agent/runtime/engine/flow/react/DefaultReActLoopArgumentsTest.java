package com.g2rain.agent.runtime.engine.flow.react;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.react.ReActLoopContext;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.task.NodeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.engine.model.ModelGateway;
import com.g2rain.agent.runtime.engine.state.DefaultNodeScope;
import com.g2rain.agent.runtime.engine.state.DefaultTurnScope;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.ToolInvoker;
import com.g2rain.agent.runtime.engine.tools.finish.NodeFinisher;
import com.g2rain.agent.runtime.engine.tools.guard.EvidenceReader;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.event.StepEvents;
import com.g2rain.agent.runtime.spec.ArgSpec;
import com.g2rain.agent.runtime.spec.ToolSpec;
import com.g2rain.agent.runtime.support.ArgsBrief;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.model.nexus.enums.MessageRole;
import com.g2rain.model.nexus.schema.ChatCompletionChoice;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.schema.ChatToolCall;
import com.g2rain.model.nexus.schema.ChatToolCallFunction;
import com.g2rain.model.nexus.vo.ChatCompletionResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultReActLoop} 动作入参脱敏用例 A4。
 *
 * @author alpha
 * @since 2026/11/22
 */
class DefaultReActLoopArgumentsTest {

    /** A4：写调用被闸门推迟 → 动作 arguments 长字段完整、敏感字段为 ***。 */
    @Test
    void a4_deferredWrite_storesFullMaskedArguments() {
        String body = "y".repeat(5000);
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("body", body);
        args.put("secret", "plain");
        String raw = JsonCodecFactory.instance().obj2str(args);

        RuntimeProperties properties = new RuntimeProperties();
        properties.getReact().setMaxRounds(1);
        properties.getReact().setMaxNoProgressRounds(1);

        ModelGateway modelGateway = mock(ModelGateway.class);
        when(modelGateway.chat(any(), any(), any())).thenReturn(toolCall("save", raw));

        ToolInvoker toolInvoker = mock(ToolInvoker.class);
        when(toolInvoker.invoke(any())).thenReturn(Observation.deferred(
            RuntimeErrorCode.APPROVAL_REQUIRED, "need confirm", "ask approval"));

        RuntimeStateFacade stateFacade = mock(RuntimeStateFacade.class);
        PromptAssembler promptAssembler = mock(PromptAssembler.class);
        when(promptAssembler.assemble(any())).thenReturn(mock(com.g2rain.model.nexus.dto.ChatCompletionRequest.class));
        EvidenceReader evidenceReader = mock(EvidenceReader.class);
        when(evidenceReader.upstreamExtracts(any())).thenReturn(List.of());
        StepEvents stepEvents = mock(StepEvents.class);
        when(stepEvents.start(any(), any(), any(), any())).thenReturn("step");

        DefaultReActLoop loop = new DefaultReActLoop(properties, modelGateway, toolInvoker, stateFacade,
            promptAssembler, evidenceReader, stepEvents, mock(NodeFinisher.class));

        ArgSpec secret = new ArgSpec();
        secret.setSensitive(true);
        ToolSpec tool = new ToolSpec();
        tool.setArgs(Map.of("secret", secret));
        SkillContract contract = new SkillContract();
        contract.setTools(Map.of("save", tool));
        Skill skill = new Skill("s1", null, List.of("save"), null, null, contract, null);

        TaskNode node = new TaskNode();
        node.setNodeId("n1");
        node.setSkillKey("s1");
        node.setStatus(NodeStatus.RUNNING);
        TaskGraph graph = new TaskGraph();
        graph.setNodes(List.of(node));
        TaskMetadata meta = new TaskMetadata();
        meta.setTaskId("t1");
        meta.setCurrentNodeId("n1");
        ConversationFence fence = new ConversationFence("c1", "u1", "t1", 1L, "trace", 1L);
        DefaultTurnScope turn = new DefaultTurnScope("1", "u1", "trace", fence, List.of(), List.of(), new HashMap<>(),
            meta, graph, null, new HashMap<>(), null, new HashMap<>(), 60);
        DefaultNodeScope scope = new DefaultNodeScope(turn, "n1", Map.of(), Map.of(), Map.of(), new ArrayList<>(),
            new ArrayList<>(), 0, 0L);

        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("save");
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);

        GraphFrame frame = new GraphFrame(turn, List.of(skill), null, null, null, null, null);
        loop.run(new ReActLoopContext(frame, node, scope, new ArrayList<>(), List.of(callback)));

        ArgumentCaptor<NodeAction> action = ArgumentCaptor.forClass(NodeAction.class);
        verify(stateFacade).act(any(), action.capture());
        JsonNode stored = JsonCodecFactory.instance().str2node(action.getValue().arguments());
        assertThat(stored.get("body").asString()).isEqualTo(body);
        assertThat(stored.get("secret").asString()).isEqualTo(ArgsBrief.MASK);
    }

    private static ChatCompletionResponse toolCall(String toolName, String arguments) {
        ChatToolCallFunction function = new ChatToolCallFunction();
        function.setName(toolName);
        function.setArguments(arguments);
        ChatToolCall call = new ChatToolCall();
        call.setId("call-1");
        call.setFunction(function);
        ChatMessage message = new ChatMessage();
        message.setRole(MessageRole.ASSISTANT);
        message.setContent("");
        message.setToolCalls(List.of(call));
        ChatCompletionChoice choice = new ChatCompletionChoice();
        choice.setMessage(message);
        choice.setFinishReason("tool_calls");
        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setChoices(List.of(choice));
        return response;
    }
}
