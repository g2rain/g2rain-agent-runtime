package com.g2rain.agent.runtime.support.trace;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.decision.TurnMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 轨迹事件的字段格式与环节开关。
 *
 * @author alpha
 * @since 2026/10/11
 */
class TraceEventTest {

    @AfterEach
    void reset() {
        Trace.bind(new RuntimeProperties.Tracing());
    }

    @Test
    void formatsValues() {
        assertThat(TraceEvent.format(null)).isEqualTo("-");
        assertThat(TraceEvent.format(TurnMode.START_FRESH)).isEqualTo("START_FRESH");
        assertThat(TraceEvent.format(List.of("a", "b"))).isEqualTo("[a,b]");
        assertThat(TraceEvent.format(42)).isEqualTo("42");
        assertThat(TraceEvent.format("a b")).isEqualTo("\"a b\"");
        assertThat(TraceEvent.format("k=v")).isEqualTo("\"k=v\"");
        assertThat(TraceEvent.format("")).isEqualTo("\"\"");
    }

    @Test
    void truncatesLongValues() {
        String formatted = TraceEvent.format("x".repeat(TraceEvent.MAX_VALUE_CHARS + 50));

        assertThat(formatted).hasSize(TraceEvent.MAX_VALUE_CHARS + 1).endsWith("…");
    }

    @Test
    void buildsOneLine() {
        TraceEvent event = Trace.at(TraceStage.DECISION, "settled")
            .kv("scene", "FRESH")
            .kv("settled", TurnMode.START_FRESH);

        assertThat(event.toString()).isEqualTo("[Trace][DECISION] settled scene=FRESH settled=START_FRESH");
    }

    @Test
    void bodyKeepsRawTextOnOneLine() {
        TraceEvent event = Trace.at(TraceStage.MODEL, "payload").body("{\"a\": 1}\n{\"b\": 2}", 100);

        assertThat(event.toString()).isEqualTo("[Trace][MODEL] payload body={\"a\": 1}\\n{\"b\": 2}");
    }

    @Test
    void disabledStageIsNoop() {
        RuntimeProperties.Tracing tracing = new RuntimeProperties.Tracing();
        tracing.setStages(EnumSet.of(TraceStage.MODEL));
        Trace.bind(tracing);

        assertThat(Trace.enabled(TraceStage.REACT)).isFalse();
        assertThat(Trace.at(TraceStage.REACT, "round").kv("round", 1).toString()).isEmpty();
        assertThat(Trace.at(TraceStage.MODEL, "call").toString()).isNotEmpty();
    }

    @Test
    void masterSwitchDisablesAll() {
        RuntimeProperties.Tracing tracing = new RuntimeProperties.Tracing();
        tracing.setEnabled(false);
        Trace.bind(tracing);

        assertThat(Trace.enabled(TraceStage.ENTRY)).isFalse();
    }
}
