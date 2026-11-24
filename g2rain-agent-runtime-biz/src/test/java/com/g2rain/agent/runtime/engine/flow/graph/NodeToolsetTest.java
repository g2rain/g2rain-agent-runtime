package com.g2rain.agent.runtime.engine.flow.graph;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCallback;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCallbackProvider;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCategory;
import com.g2rain.agent.runtime.support.tool.CapabilityRegistry;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link NodeToolset}：能力工具插在内置与业务工具之间；无能力时列表不变。
 *
 * @author alpha
 * @since 2026/11/24
 */
class NodeToolsetTest {

    @Test
    void withoutCapabilitiesMatchesBuiltinOnly() {
        ToolCallback builtin = callback("builtin_env_now");
        BuiltinToolCallbackProvider builtins = mock(BuiltinToolCallbackProvider.class);
        when(builtins.getToolCallbacks()).thenReturn(new ToolCallback[]{builtin});

        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        when(capabilities.callbacks()).thenReturn(List.of());

        NodeToolset toolset = new NodeToolset(builtins, capabilities, mock(McpSyncClient.class), new RuntimeProperties());
        TaskNode node = nodeWithoutSkill();
        List<ToolCallback> tools = toolset.of(node, frameWithoutSkills());

        assertThat(tools).containsExactly(builtin);
    }

    @Test
    void capabilityInsertedBetweenBuiltinAndBusinessSlot() {
        ToolCallback builtin = callback("builtin_env_now");
        BuiltinToolCallbackProvider builtins = mock(BuiltinToolCallbackProvider.class);
        when(builtins.getToolCallbacks()).thenReturn(new ToolCallback[]{builtin});

        ToolCallback capabilityDelegate = callback("sample_lookup");
        BuiltinToolCallback capability = new BuiltinToolCallback(
            capabilityDelegate, BuiltinToolCategory.CAPABILITY, BuiltinToolAction.CAPABILITY, RuntimeMessage.STEP_TOOL);
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        when(capabilities.callbacks()).thenReturn(List.of(capability));

        NodeToolset toolset = new NodeToolset(builtins, capabilities, mock(McpSyncClient.class), new RuntimeProperties());
        List<ToolCallback> tools = toolset.of(nodeWithoutSkill(), frameWithoutSkills());

        assertThat(tools).containsExactly(builtin, capability);
        assertThat(tools.get(1)).isInstanceOf(BuiltinToolCallback.class);
        assertThat(((BuiltinToolCallback) tools.get(1)).action()).isEqualTo(BuiltinToolAction.CAPABILITY);
    }

    private static TaskNode nodeWithoutSkill() {
        TaskNode node = new TaskNode();
        node.setNodeId("n1");
        node.setSkillKey(null);
        return node;
    }

    private static GraphFrame frameWithoutSkills() {
        GraphFrame frame = mock(GraphFrame.class);
        when(frame.skills()).thenReturn(List.<Skill>of());
        return frame;
    }

    private static ToolCallback callback(String name) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }
}
