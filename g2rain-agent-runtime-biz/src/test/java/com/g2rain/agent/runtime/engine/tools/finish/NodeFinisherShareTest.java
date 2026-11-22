package com.g2rain.agent.runtime.engine.tools.finish;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.OutputSpec;
import com.g2rain.agent.runtime.domain.hydrate.ShareScope;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.state.DefaultNodeScope;
import com.g2rain.agent.runtime.engine.state.DefaultTurnScope;
import com.g2rain.agent.runtime.engine.state.EntityExtractor;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.spec.SourceType;
import com.g2rain.agent.runtime.support.PlatformCatalog;
import com.g2rain.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link NodeFinisher#finish} 共享校验顺序用例 N8、N9。
 *
 * @author alpha
 * @since 2026/11/22
 */
class NodeFinisherShareTest {

    private FinishPolicy finishPolicy;

    private PlatformCatalog platformCatalog;

    private NodeFinisher finisher;

    @BeforeEach
    void setUp() {
        finishPolicy = mock(FinishPolicy.class);
        when(finishPolicy.evaluate(any())).thenReturn(FinishDecision.allow());
        platformCatalog = mock(PlatformCatalog.class);
        finisher = new NodeFinisher(finishPolicy, new EntityExtractor(platformCatalog, mock(RuntimeProperties.class)));
    }

    /** N8：平台同名 share 输出 → 拒绝且未 DONE、未写共享。 */
    @Test
    void n8_finish_platformShareKey_rejectsBeforeDone() {
        when(platformCatalog.keys()).thenReturn(Set.of("userId"));
        Fixture fixture = fixture("userId", ShareScope.TASK, "u1");

        assertThatThrownBy(() -> finisher.finish(fixture.ctx(), "ok"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(RuntimeErrorCode.PLATFORM_FIELD_FORBIDDEN.code());
        assertThat(fixture.ctx().getNodeStatus()).isEqualTo(NodeStatus.RUNNING);
        assertThat(fixture.turn().shared()).isEmpty();
    }

    /** N9：正常 finish → DONE 且写共享。 */
    @Test
    void n9_finish_ok_writesShare() {
        when(platformCatalog.keys()).thenReturn(Set.of("userId"));
        Fixture fixture = fixture("articleId", ShareScope.TASK, "a1");

        assertThat(finisher.finish(fixture.ctx(), "done")).isTrue();
        assertThat(fixture.ctx().getNodeStatus()).isEqualTo(NodeStatus.DONE);
        assertThat(fixture.turn().shared()).containsKey("articleId");
    }

    private Fixture fixture(String key, ShareScope share, Object value) {
        OutputSpec spec = new OutputSpec();
        spec.setShare(share);
        SkillContract contract = new SkillContract();
        contract.setOutputs(Map.of(key, spec));
        Skill skill = new Skill("skill", null, contract);

        TaskMetadata meta = new TaskMetadata();
        meta.setTaskId("t1");
        meta.setConversationId("c1");
        TaskNode node = new TaskNode();
        node.setNodeId("n1");
        node.setSkillKey("skill");
        TaskGraph graph = new TaskGraph();
        graph.setNodes(List.of(node));
        ConversationFence fence = new ConversationFence("c1", "u1", "t1", 1L, "trace", System.currentTimeMillis());
        DefaultTurnScope turn = new DefaultTurnScope("1", "u1", "trace", fence, List.of(), List.of(), new HashMap<>(),
            meta, graph, null, new HashMap<>(), null, new HashMap<>(), 60);
        Map<String, Sourced<?>> outputs = new HashMap<>();
        outputs.put(key, new Sourced<>(value, new Source(SourceType.EVIDENCE, "e1", "id"), false));
        DefaultNodeScope scope = new DefaultNodeScope(turn, "n1", Map.of(), outputs, Map.of(), List.of(), List.of(), 0, 0);
        GraphFrame frame = new GraphFrame(turn, List.of(skill), null, null, null, null, null);
        return new Fixture(turn, new ToolExecutionContext(frame, node, scope));
    }

    private record Fixture(DefaultTurnScope turn, ToolExecutionContext ctx) {
    }
}
