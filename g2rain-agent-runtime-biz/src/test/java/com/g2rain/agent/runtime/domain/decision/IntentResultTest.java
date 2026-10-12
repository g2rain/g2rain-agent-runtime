package com.g2rain.agent.runtime.domain.decision;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图结论规范化：agentId 保留条件、前情开关与文本清理。
 *
 * @author alpha
 * @since 2026/10/10
 */
class IntentResultTest {

    private static final List<AgentSelection> CANDIDATES = List.of(AgentSelection.of(1001L, "开票助手", null));

    private static IntentResult result(TurnMode mode, Long agentId) {
        IntentResult result = new IntentResult();
        result.setMode(mode);
        result.setAgentId(agentId);
        return result;
    }

    @Test
    void keepsListedAgentForSelectingMode() {
        assertThat(result(TurnMode.START_FRESH, 1001L).complete(CANDIDATES).getAgentId()).isEqualTo(1001L);
    }

    @Test
    void clearsAgentForNonSelectingMode() {
        assertThat(result(TurnMode.CONTINUE_TASK, 1001L).complete(CANDIDATES).getAgentId()).isNull();
    }

    @Test
    void clearsAgentOutsideCandidates() {
        assertThat(result(TurnMode.START_FRESH, 9999L).complete(CANDIDATES).getAgentId()).isNull();
        assertThat(result(TurnMode.START_FRESH, 1001L).complete(List.of()).getAgentId()).isNull();
        assertThat(result(TurnMode.START_FRESH, 1001L).complete(null).getAgentId()).isNull();
    }

    @Test
    void derivesContinuityAndTrimsText() {
        IntentResult result = result(TurnMode.START_WITH_CONTEXT, 1001L);
        result.setUserReply("  ");
        result.setGoalSummary(" 查退款进度 ");
        result.complete(CANDIDATES);

        assertThat(result.isUseContinuity()).isTrue();
        assertThat(result.getUserReply()).isNull();
        assertThat(result.getGoalSummary()).isEqualTo("查退款进度");
    }
}
