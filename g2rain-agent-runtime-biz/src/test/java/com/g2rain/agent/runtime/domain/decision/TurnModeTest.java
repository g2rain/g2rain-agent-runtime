package com.g2rain.agent.runtime.domain.decision;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模式名宽松解析与选人模式判定。
 *
 * @author alpha
 * @since 2026/10/10
 */
class TurnModeTest {

    @Test
    void ofIgnoresCaseAndWhitespace() {
        assertThat(TurnMode.of(" start_fresh ")).isEqualTo(TurnMode.START_FRESH);
        assertThat(TurnMode.of("REPLY_ONLY")).isEqualTo(TurnMode.REPLY_ONLY);
    }

    @Test
    void ofReturnsNullForUnknownOrBlank() {
        assertThat(TurnMode.of("NEW_TASK")).isNull();
        assertThat(TurnMode.of(" ")).isNull();
        assertThat(TurnMode.of(null)).isNull();
    }

    @Test
    void selectsAgentOnlyForStartAndSwitch() {
        assertThat(TurnMode.START_FRESH.selectsAgent()).isTrue();
        assertThat(TurnMode.START_WITH_CONTEXT.selectsAgent()).isTrue();
        assertThat(TurnMode.SWITCH_AGENT.selectsAgent()).isTrue();
        assertThat(TurnMode.CONTINUE_TASK.selectsAgent()).isFalse();
        assertThat(TurnMode.REPLACE_TASK.selectsAgent()).isFalse();
        assertThat(TurnMode.CANCEL_TASK.selectsAgent()).isFalse();
        assertThat(TurnMode.REPLY_ONLY.selectsAgent()).isFalse();
        assertThat(TurnMode.UNCERTAIN.selectsAgent()).isFalse();
    }
}
