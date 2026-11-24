package com.g2rain.agent.runtime.support.tool;

import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.ToolExecutionContexts;
import com.g2rain.agent.runtime.engine.tools.guard.EvidenceReader;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.i18n.RuntimeMessageRegistry;
import com.g2rain.agent.runtime.spi.Capability;
import com.g2rain.agent.runtime.spi.CapabilityCall;
import com.g2rain.agent.runtime.spi.CapabilityResult;
import com.g2rain.agent.runtime.spi.CapabilitySpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link CapabilityToolCallback} 成功返回与失败抛出。
 *
 * @author alpha
 * @since 2026/11/24
 */
class CapabilityToolCallbackTest {

    @Test
    void successReturnsContent() {
        Capability capability = capability(false, call -> CapabilityResult.success("{\"ok\":true}"));
        CapabilityToolCallback callback = new CapabilityToolCallback(capability, mock(EvidenceReader.class),
            mock(RuntimeStateFacade.class));

        String result = callback.call("{}", toolContext());
        assertThat(result).isEqualTo("{\"ok\":true}");
    }

    @Test
    void externalSuccess_isWrapped() {
        Capability capability = capability(true, call -> CapabilityResult.success("payload"));
        CapabilityToolCallback callback = new CapabilityToolCallback(capability, mock(EvidenceReader.class),
            mock(RuntimeStateFacade.class));

        String result = callback.call("{}", toolContext());
        assertThat(result).isEqualTo(ExternalContent.wrap("builtin_sample_lookup", "payload"));
    }

    @Test
    void label_usesRegistryWhenPresent() {
        String name = "builtin_sample_label_hit";
        RuntimeMessageRegistry.put(CapabilityToolCallback.MESSAGE_CODE_PREFIX + name, "en-US", "Sample lookup");
        CapabilityToolCallback callback = new CapabilityToolCallback(
            capability(name, "示例查询", false, call -> CapabilityResult.success("{}")),
            mock(EvidenceReader.class), mock(RuntimeStateFacade.class));

        assertThat(callback.label("en-US")).isEqualTo("Sample lookup");
    }

    @Test
    void label_fallsBackToSpecWhenMissing() {
        CapabilityToolCallback callback = new CapabilityToolCallback(
            capability("builtin_sample_label_miss", "示例查询", false, call -> CapabilityResult.success("{}")),
            mock(EvidenceReader.class), mock(RuntimeStateFacade.class));

        assertThat(callback.label("en-US")).isEqualTo("示例查询");
    }

    @Test
    void externalFailure_isNotWrapped() {
        Capability capability = capability(true, call -> CapabilityResult.failure("条件不满足", "换个条件再试"));
        CapabilityToolCallback callback = new CapabilityToolCallback(capability, mock(EvidenceReader.class),
            mock(RuntimeStateFacade.class));

        assertThatThrownBy(() -> callback.call("{}", toolContext()))
            .isInstanceOf(CapabilityFailure.class)
            .satisfies(ex -> {
                CapabilityFailure failure = (CapabilityFailure) ex;
                assertThat(failure.reason()).isEqualTo("条件不满足");
                assertThat(failure.reason()).doesNotContain(ExternalContent.TAG);
            });
    }

    @Test
    void nonExternalSuccess_isNotWrapped() {
        Capability capability = capability(false, call -> CapabilityResult.success("plain"));
        CapabilityToolCallback callback = new CapabilityToolCallback(capability, mock(EvidenceReader.class),
            mock(RuntimeStateFacade.class));

        assertThat(callback.call("{}", toolContext())).isEqualTo("plain");
    }

    @Test
    void failureThrowsCapabilityFailure() {
        Capability capability = capability(false, call -> CapabilityResult.failure("条件不满足", "换个条件再试"));
        CapabilityToolCallback callback = new CapabilityToolCallback(capability, mock(EvidenceReader.class),
            mock(RuntimeStateFacade.class));

        assertThatThrownBy(() -> callback.call("{}", toolContext()))
            .isInstanceOf(CapabilityFailure.class)
            .satisfies(ex -> {
                CapabilityFailure failure = (CapabilityFailure) ex;
                assertThat(failure.reason()).isEqualTo("条件不满足");
                assertThat(failure.next()).isEqualTo("换个条件再试");
            });
    }

    @Test
    void unexpectedExceptionThrowsCapabilityFailure() {
        Capability capability = capability(false, call -> {
            throw new IllegalStateException("boom");
        });
        CapabilityToolCallback callback = new CapabilityToolCallback(capability, mock(EvidenceReader.class),
            mock(RuntimeStateFacade.class));

        assertThatThrownBy(() -> callback.call("{}", toolContext()))
            .isInstanceOf(CapabilityFailure.class)
            .satisfies(ex -> {
                CapabilityFailure failure = (CapabilityFailure) ex;
                assertThat(failure.reason()).isEqualTo(ModelHint.CAPABILITY_FAILED.text());
                assertThat(failure.next()).isEqualTo(ModelHint.NEXT_CAPABILITY_FALLBACK.text());
            });
    }

    @Test
    void invalidJsonThrowsCapabilityFailure() {
        Capability capability = capability(false, call -> CapabilityResult.success("{}"));
        CapabilityToolCallback callback = new CapabilityToolCallback(capability, mock(EvidenceReader.class),
            mock(RuntimeStateFacade.class));

        assertThatThrownBy(() -> callback.call("{", toolContext()))
            .isInstanceOf(CapabilityFailure.class)
            .satisfies(ex -> {
                CapabilityFailure failure = (CapabilityFailure) ex;
                assertThat(failure.reason()).startsWith("入参 JSON 非法：");
                assertThat(failure.next()).isEqualTo(ModelHint.NEXT_FIX_ARGS.text());
            });
    }

    private static ToolContext toolContext() {
        ToolExecutionContext execution = mock(ToolExecutionContext.class);
        return ToolExecutionContexts.toSpring(execution);
    }

    private static Capability capability(boolean external,
                                         java.util.function.Function<CapabilityCall, CapabilityResult> handler) {
        return capability("builtin_sample_lookup", "示例工具", external, handler);
    }

    private static Capability capability(String name, String label, boolean external,
                                         java.util.function.Function<CapabilityCall, CapabilityResult> handler) {
        return new Capability() {
            @Override
            public CapabilitySpec spec() {
                return new CapabilitySpec(name, label, "desc",
                    JsonNodeFactory.instance.objectNode(), external);
            }

            @Override
            public CapabilityResult call(CapabilityCall call) {
                return handler.apply(call);
            }
        };
    }
}
