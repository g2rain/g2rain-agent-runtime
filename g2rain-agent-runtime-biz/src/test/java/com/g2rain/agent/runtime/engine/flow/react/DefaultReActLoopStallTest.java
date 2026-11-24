package com.g2rain.agent.runtime.engine.flow.react;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.react.ReActLoopContext;
import com.g2rain.agent.runtime.domain.react.ReActLoopResult;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.engine.model.ModelGateway;
import com.g2rain.agent.runtime.engine.state.DefaultNodeScope;
import com.g2rain.agent.runtime.engine.state.DefaultTurnScope;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.state.TurnScopes;
import com.g2rain.agent.runtime.engine.tools.ToolInvoker;
import com.g2rain.agent.runtime.engine.tools.finish.NodeFinisher;
import com.g2rain.agent.runtime.engine.tools.guard.EvidenceReader;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.event.StepEvents;
import com.g2rain.model.nexus.schema.ChatCompletionChoice;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.enums.MessageRole;
import com.g2rain.model.nexus.vo.ChatCompletionResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultReActLoop} 畸形工具调用停滞用例 T8。
 *
 * @author alpha
 * @since 2026/11/22
 */
class DefaultReActLoopStallTest {

    /** T8：连续畸形工具调用达到无进展上限 → NODE_STALLED。 */
    @Test
    void t8_malformedCalls_reachStallLimit() {
        RuntimeProperties properties = new RuntimeProperties();
        properties.getReact().setMaxRounds(10);
        properties.getReact().setMaxNoProgressRounds(2);

        ModelGateway modelGateway = mock(ModelGateway.class);
        when(modelGateway.chat(any(), any(), any())).thenReturn(malformed());

        PromptAssembler promptAssembler = mock(PromptAssembler.class);
        when(promptAssembler.assemble(any())).thenReturn(mock(com.g2rain.model.nexus.dto.ChatCompletionRequest.class));

        EvidenceReader evidenceReader = mock(EvidenceReader.class);
        when(evidenceReader.upstreamExtracts(any())).thenReturn(List.of());

        StepEvents stepEvents = mock(StepEvents.class);
        when(stepEvents.start(any(), any(), any(), any())).thenReturn("step");

        DefaultReActLoop loop = new DefaultReActLoop(properties, modelGateway, mock(ToolInvoker.class),
            mock(RuntimeStateFacade.class), promptAssembler, evidenceReader, stepEvents, mock(NodeFinisher.class));

        TaskNode node = new TaskNode();
        node.setNodeId("n1");
        node.setSkillKey("skill");
        node.setStatus(NodeStatus.RUNNING);
        TaskGraph graph = new TaskGraph();
        graph.setNodes(List.of(node));

        TaskMetadata meta = new TaskMetadata();
        meta.setTaskId("t1");
        meta.setCurrentNodeId("n1");
        ConversationFence fence = new ConversationFence("c1", "u1", "t1", 1L, "trace", 1L);
        DefaultTurnScope turn = TurnScopes.create("1", "u1", "trace", fence, List.of(), List.of(), new HashMap<>(),
            meta, graph, null, new HashMap<>(), null, new HashMap<>(), 60);
        DefaultNodeScope scope = new DefaultNodeScope(turn, "n1", Map.of(), Map.of(), Map.of(), new ArrayList<>(),
            new ArrayList<>(), 0, 0L);

        TurnScope turnView = turn;
        GraphFrame frame = new GraphFrame(turnView, List.of(), null, null, null, null, null);
        ReActLoopResult result = loop.run(new ReActLoopContext(frame, node, scope, new ArrayList<>(), List.of()));

        assertThat(result.nodeStatus()).isEqualTo(NodeStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo(RuntimeErrorCode.NODE_STALLED.code());
    }

    private static ChatCompletionResponse malformed() {
        ChatMessage message = new ChatMessage();
        message.setRole(MessageRole.ASSISTANT);
        message.setContent("calling tools");
        ChatCompletionChoice choice = new ChatCompletionChoice();
        choice.setMessage(message);
        choice.setFinishReason("tool_calls");
        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setChoices(List.of(choice));
        return response;
    }
}
