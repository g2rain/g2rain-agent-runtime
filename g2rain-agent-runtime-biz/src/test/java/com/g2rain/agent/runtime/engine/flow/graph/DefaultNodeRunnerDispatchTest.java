package com.g2rain.agent.runtime.engine.flow.graph;

import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.react.ReActLoopResult;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.engine.flow.react.ReActLoop;
import com.g2rain.agent.runtime.engine.state.EvidenceFlow;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.ToolInvoker;
import com.g2rain.agent.runtime.event.StepEvents;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultNodeRunner#dispatchPending} 用例 T7。
 *
 * @author alpha
 * @since 2026/11/22
 */
class DefaultNodeRunnerDispatchTest {

    /** T7：一次 proceed 中 nodeToolset.of 只调用一次。 */
    @Test
    void t7_dispatchPending_reusesToolsFromProceed() {
        NodeToolset nodeToolset = mock(NodeToolset.class);
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("write");
        when(tool.getToolDefinition()).thenReturn(definition);
        when(nodeToolset.of(any(), any())).thenReturn(List.of(tool));

        ReActLoop reActLoop = mock(ReActLoop.class);
        when(reActLoop.run(any())).thenReturn(new ReActLoopResult(NodeStatus.DONE, "ok", null, null));

        RuntimeStateFacade runtimeStateFacade = mock(RuntimeStateFacade.class);
        NodeScope nodeScope = mock(NodeScope.class);
        when(nodeScope.rounds()).thenReturn(1);
        when(runtimeStateFacade.open(any(), any(), any())).thenReturn(nodeScope);
        when(runtimeStateFacade.history(any(), anyList(), any())).thenReturn(List.of());

        ToolInvoker toolInvoker = mock(ToolInvoker.class);
        when(toolInvoker.invoke(any())).thenReturn(Observation.success("ok", null));

        StepEvents stepEvents = mock(StepEvents.class);
        when(stepEvents.start(any(), any(), any(), any())).thenReturn("step-1");

        DefaultNodeRunner runner = new DefaultNodeRunner(new DefaultNodeStateMachine(), nodeToolset,
            mock(WriteVerifier.class), reActLoop, toolInvoker, runtimeStateFacade, mock(EvidenceFlow.class),
            stepEvents);

        TaskNode node = new TaskNode();
        node.setNodeId("n1");
        node.setSkillKey("skill");
        node.setStatus(NodeStatus.READY);
        TaskGraph graph = new TaskGraph();
        graph.setNodes(List.of(node));

        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setNodeId("n1");
        checkpoint.setType(CheckpointType.APPROVAL);
        checkpoint.setPendingAction(new PendingAction("write", JsonNodeFactory.instance.objectNode()));

        TaskMetadata meta = new TaskMetadata();
        meta.setTaskId("t1");
        meta.setCurrentNodeId("n1");
        TurnScope turn = mock(TurnScope.class);
        when(turn.task()).thenReturn(Optional.of(meta));
        when(turn.graph()).thenReturn(graph);
        when(turn.checkpoint()).thenReturn(Optional.of(checkpoint));
        when(turn.resumed()).thenReturn(Optional.of(checkpoint));
        when(turn.conversation()).thenReturn(new ConversationFence("c1", "u1", "t1", 1L, "trace", 1L));

        GraphFrame frame = new GraphFrame(turn, List.of(), null, ResumeAction.CONFIRM, null, null, null);
        runner.run(node, frame);

        verify(nodeToolset, times(1)).of(any(), any());
        verify(toolInvoker).invoke(any());
    }
}
