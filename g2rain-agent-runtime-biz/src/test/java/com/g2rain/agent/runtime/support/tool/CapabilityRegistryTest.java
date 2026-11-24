package com.g2rain.agent.runtime.support.tool;

import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.guard.EvidenceReader;
import com.g2rain.agent.runtime.spi.Capability;
import com.g2rain.agent.runtime.spi.CapabilityCall;
import com.g2rain.agent.runtime.spi.CapabilityResult;
import com.g2rain.agent.runtime.spi.CapabilitySpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CapabilityRegistry} 启动校验。
 *
 * @author alpha
 * @since 2026/11/24
 */
class CapabilityRegistryTest {

    @Test
    void emptyListSucceeds() {
        CapabilityRegistry registry = new CapabilityRegistry(List.of(), builtins(), mock(EvidenceReader.class),
            mock(RuntimeStateFacade.class));
        assertThat(registry.callbacks()).isEmpty();
    }

    @Test
    void invalidNameFailsWithNameAndSource() {
        Capability capability = capability("Builtin_Bad", "示例");
        assertThatThrownBy(() -> new CapabilityRegistry(List.of(capability), builtins(), mock(EvidenceReader.class),
            mock(RuntimeStateFacade.class)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Builtin_Bad")
            .hasMessageContaining(capability.getClass().getName());
    }

    @Test
    void builtinPrefixFails() {
        Capability capability = capability("builtin_lookup", "示例");
        assertThatThrownBy(() -> new CapabilityRegistry(List.of(capability), builtins(), mock(EvidenceReader.class),
            mock(RuntimeStateFacade.class)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("builtin_lookup")
            .hasMessageContaining(capability.getClass().getName());
    }

    @Test
    void conflictsWithBuiltinFails() {
        Capability capability = capability("sample_lookup", "示例");
        assertThatThrownBy(() -> new CapabilityRegistry(List.of(capability), builtins("sample_lookup"),
            mock(EvidenceReader.class), mock(RuntimeStateFacade.class)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sample_lookup")
            .hasMessageContaining(capability.getClass().getName());
    }

    @Test
    void duplicateNameFailsWithBothSources() {
        Capability first = capability("sample_lookup", "甲");
        Capability second = capability("sample_lookup", "乙");
        assertThatThrownBy(() -> new CapabilityRegistry(List.of(first, second), builtins(), mock(EvidenceReader.class),
            mock(RuntimeStateFacade.class)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sample_lookup")
            .hasMessageContaining(first.getClass().getName())
            .hasMessageContaining(second.getClass().getName());
    }

    @Test
    void registersDecoratedCallback() {
        Capability capability = capability("sample_lookup", "示例查询");
        CapabilityRegistry registry = new CapabilityRegistry(List.of(capability), builtins(), mock(EvidenceReader.class),
            mock(RuntimeStateFacade.class));
        assertThat(registry.callbacks()).hasSize(1);
        BuiltinToolCallback callback = (BuiltinToolCallback) registry.callbacks().getFirst();
        assertThat(callback.category()).isEqualTo(BuiltinToolCategory.CAPABILITY);
        assertThat(callback.action()).isEqualTo(BuiltinToolAction.CAPABILITY);
        assertThat(callback.getToolDefinition().name()).isEqualTo("sample_lookup");
    }

    private static BuiltinToolCallbackProvider builtins(String... names) {
        BuiltinToolCallbackProvider provider = mock(BuiltinToolCallbackProvider.class);
        ToolCallback[] callbacks = new ToolCallback[names.length];
        for (int i = 0; i < names.length; i++) {
            ToolDefinition definition = mock(ToolDefinition.class);
            when(definition.name()).thenReturn(names[i]);
            ToolCallback callback = mock(ToolCallback.class);
            when(callback.getToolDefinition()).thenReturn(definition);
            callbacks[i] = callback;
        }
        when(provider.getToolCallbacks()).thenReturn(callbacks);
        return provider;
    }

    private static Capability capability(String name, String label) {
        return new Capability() {
            @Override
            public CapabilitySpec spec() {
                return new CapabilitySpec(name, Map.of("zh-CN", label), "desc",
                    JsonNodeFactory.instance.objectNode(), false);
            }

            @Override
            public CapabilityResult call(CapabilityCall call) {
                return CapabilityResult.success("{}");
            }
        };
    }
}
