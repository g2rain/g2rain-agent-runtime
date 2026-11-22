package com.g2rain.agent.runtime.support.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link McpToolCallbackProvider} 用例 T9。
 *
 * @author alpha
 * @since 2026/11/22
 */
class McpToolCallbackProviderTest {

    /** T9：注册中心多返回的工具被过滤掉。 */
    @Test
    void t9_getToolCallbacks_filtersUnexpectedNames() {
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Tool allowed = McpSchema.Tool.builder("allowed", Map.of()).build();
        McpSchema.Tool unexpected = McpSchema.Tool.builder("unexpected", Map.of()).build();
        when(client.listTools(isNull(), any()))
            .thenReturn(new McpSchema.ListToolsResult(List.of(allowed, unexpected), null, null));

        McpToolCallbackProvider provider = new McpToolCallbackProvider(client, Set.of("allowed"), 0);
        ToolCallback[] callbacks = provider.getToolCallbacks();

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("allowed");
    }
}
