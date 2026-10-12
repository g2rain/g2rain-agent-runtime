package com.g2rain.agent.runtime.support.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 轨迹作用域对 MDC 的写入与恢复。
 *
 * @author alpha
 * @since 2026/10/11
 */
class TraceScopeTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void turnClearsStaleIdsAndRestoresOnClose() {
        MDC.put(TraceScope.TASK_ID, "stale-task");
        MDC.put(TraceScope.NODE_ID, "stale-node");

        try (TraceScope ignored = TraceScope.turn("c-1")) {
            assertThat(MDC.get(TraceScope.CONVERSATION_ID)).isEqualTo("c-1");
            assertThat(MDC.get(TraceScope.TASK_ID)).isNull();
            assertThat(MDC.get(TraceScope.NODE_ID)).isNull();

            TraceScope.bindTask("t-1");
            assertThat(MDC.get(TraceScope.TASK_ID)).isEqualTo("t-1");
        }

        assertThat(MDC.get(TraceScope.CONVERSATION_ID)).isNull();
        assertThat(MDC.get(TraceScope.TASK_ID)).isEqualTo("stale-task");
        assertThat(MDC.get(TraceScope.NODE_ID)).isEqualTo("stale-node");
    }

    @Test
    void nestedNodeRestoresParent() {
        try (TraceScope outer = TraceScope.node("n-1")) {
            try (TraceScope inner = TraceScope.node("n-2")) {
                assertThat(MDC.get(TraceScope.NODE_ID)).isEqualTo("n-2");
            }
            assertThat(MDC.get(TraceScope.NODE_ID)).isEqualTo("n-1");
        }

        assertThat(MDC.get(TraceScope.NODE_ID)).isNull();
    }

    @Test
    void blankValueRemovesKey() {
        try (TraceScope ignored = TraceScope.turn("c-1")) {
            TraceScope.bindTask("t-1");
            TraceScope.bindTask(" ");
            assertThat(MDC.get(TraceScope.TASK_ID)).isNull();
        }
    }
}
