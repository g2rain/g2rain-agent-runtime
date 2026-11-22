package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.spec.SideEffect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultNodeScope#addEvidence} 用例 T5。
 *
 * @author alpha
 * @since 2026/11/22
 */
class DefaultNodeScopeEvidenceTest {

    /** T5：更新已有证据时原位替换，顺序不变。 */
    @Test
    void t5_addEvidence_replacesInPlace() {
        DefaultNodeScope scope = scope();
        EvidenceHead a = head("A", EvidenceStatus.STARTED);
        EvidenceHead b = head("B", EvidenceStatus.SUCCESS);
        EvidenceHead aNext = head("A", EvidenceStatus.CANCELLED);

        scope.addEvidence(a);
        scope.addEvidence(b);
        scope.addEvidence(aNext);

        List<EvidenceHead> evidence = scope.evidence();
        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).evidenceId()).isEqualTo("A");
        assertThat(evidence.get(0).status()).isEqualTo(EvidenceStatus.CANCELLED);
        assertThat(evidence.get(1).evidenceId()).isEqualTo("B");
    }

    private static DefaultNodeScope scope() {
        ConversationFence fence = new ConversationFence("c1", "u1", null, 0L, "trace", System.currentTimeMillis());
        DefaultTurnScope turn = new DefaultTurnScope("1", "u1", "trace", fence, List.of(), List.of(), new HashMap<>(),
            null, new TaskGraph(), null, new HashMap<>(), null, new HashMap<>(), 60);
        return new DefaultNodeScope(turn, "n1", Map.of(), Map.of(), Map.of(), new ArrayList<>(), new ArrayList<>(),
            0, 0L);
    }

    private static EvidenceHead head(String id, EvidenceStatus status) {
        return new EvidenceHead(id, "tool", SideEffect.NONE, status, null, null, null, FailureKind.NONE,
            null, null, null, null, null, null, null, 1, "trace", null);
    }
}
