package com.g2rain.agent.runtime.engine.flow.react;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.support.trace.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultPromptAssembler} 丢块 Trace 用例 P10。
 *
 * @author alpha
 * @since 2026/11/22
 */
class DefaultPromptAssemblerDropTest {

    @AfterEach
    void resetTrace() {
        Trace.bind(new RuntimeProperties.Tracing());
    }

    /** P10：丢弃尾部两块 → 记录 prompt_blocks_dropped，标签为这两块。 */
    @Test
    void p10_droppedBlocks_tracesTags() {
        Trace.bind(new RuntimeProperties.Tracing());
        Logger logger = (Logger) LoggerFactory.getLogger("g2rain.agent.trace");
        Level previous = logger.getLevel();
        logger.setLevel(Level.WARN);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ReactProjectionFactory factory = mock(ReactProjectionFactory.class);
            when(factory.blocks(any())).thenReturn(List.of(
                new ReactProjection.TaggedBlock("node_goal", Map.of("k", "v")),
                new ReactProjection.TaggedBlock("shared", Map.of("pad", "x".repeat(80))),
                new ReactProjection.TaggedBlock("prior", Map.of("pad", "y".repeat(80)))
            ));
            RuntimeProperties properties = new RuntimeProperties();
            properties.getReact().setWorkspaceSliceMaxChars(60);
            DefaultPromptAssembler assembler = new DefaultPromptAssembler(factory, properties);
            NodeScope node = mock(NodeScope.class);
            when(node.nodeId()).thenReturn("n1");
            PromptAssembleRequest request = new PromptAssembleRequest(List.of(), List.of(), node, "n1", List.of(),
                null, null, null, null, null, null, null, null, false, List.of());

            assembler.assemble(request);

            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(line -> line.contains("prompt_blocks_dropped")
                    && line.contains("tags=[shared,prior]"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }
}
