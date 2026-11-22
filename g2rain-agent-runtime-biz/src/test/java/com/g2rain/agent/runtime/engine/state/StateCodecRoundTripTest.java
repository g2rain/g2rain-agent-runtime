package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.spec.SideEffect;
import com.g2rain.agent.runtime.spec.SourceType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StateCodec} 往返用例 C1～C4。
 *
 * @author alpha
 * @since 2026/11/22
 */
class StateCodecRoundTripTest {

    /** C1：栅栏往返（含 null 字段）；编码不含 version 与 null 字段。 */
    @Test
    void c1_fence_roundTrip_skipsVersionAndNulls() {
        ConversationFence fence = new ConversationFence("c1", "u1", null, 7L, null, 1_700_000_000_000L);

        Map<String, String> encoded = StateCodec.fenceHash(fence);

        assertThat(encoded).doesNotContainKey("version");
        assertThat(encoded).doesNotContainKeys("activeTaskId", "lastTraceId");
        assertThat(encoded).containsKey("userId");

        Map<String, String> withVersion = new HashMap<>(encoded);
        withVersion.put("version", "7");
        assertThat(StateCodec.fence("c1", withVersion)).isEqualTo(fence);
    }

    /** C2：共享值往返；marked true 带 MARK，解码跳过。 */
    @Test
    void c2_shared_roundTrip_markOptional() {
        Map<String, Sourced<?>> shared = Map.of(
            "k", new Sourced<>("v", new Source(SourceType.USER, "m1", ""), false));

        Map<String, String> marked = StateCodec.sharedHash(shared, true);
        Map<String, String> plain = StateCodec.sharedHash(shared, false);

        assertThat(marked).containsKey(HashCodec.MARK);
        assertThat(plain).doesNotContainKey(HashCodec.MARK);
        assertThat(StateCodec.shared(marked)).isEqualTo(shared);
        assertThat(StateCodec.shared(plain)).isEqualTo(shared);
    }

    /** C3：断点、前情 null ↔ ""。 */
    @Test
    void c3_checkpointAndPrior_nullEmptyRoundTrip() {
        assertThat(StateCodec.checkpointValue(null)).isEmpty();
        assertThat(StateCodec.priorValue(null)).isEmpty();
        assertThat(StateCodec.checkpoint("")).isNull();
        assertThat(StateCodec.prior("")).isNull();
        assertThat(StateCodec.checkpoint(null)).isNull();
        assertThat(StateCodec.prior(null)).isNull();

        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setCheckpointId("cp-1");
        Prior prior = new Prior(List.of(), Map.of(), List.of());
        assertThat(StateCodec.checkpoint(StateCodec.checkpointValue(checkpoint)).getCheckpointId())
            .isEqualTo("cp-1");
        assertThat(StateCodec.prior(StateCodec.priorValue(prior))).isEqualTo(prior);
    }

    /** C4：证据往返；乱序轮次解码后按轮次排序，跳过 MARK。 */
    @Test
    void c4_evidence_roundTrip_sortsByRoundSkipsMark() {
        EvidenceHead late = head("e2", 2);
        EvidenceHead early = head("e1", 1);
        Map<String, String> encoded = StateCodec.evidenceHash(List.of(late, early));

        assertThat(encoded).containsKey(HashCodec.MARK);
        List<EvidenceHead> decoded = StateCodec.evidence(encoded);
        assertThat(decoded).extracting(EvidenceHead::evidenceId).containsExactly("e1", "e2");
        assertThat(decoded).extracting(EvidenceHead::round).containsExactly(1, 2);
    }

    private static EvidenceHead head(String id, int round) {
        return new EvidenceHead(id, "tool", SideEffect.NONE, EvidenceStatus.SUCCESS, true, false, null,
            FailureKind.NONE, null, null, "{}", "ok", null, null, null, round, "trace", null);
    }
}
