package com.g2rain.agent.runtime.support.mcp;

import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.util.List;
import java.util.Objects;

/**
 * 把远端 MCP 工具适配成 Spring AI {@link ToolCallback}，供 ToolInvoker 调用。
 *
 * <p>{@code call} 组装 {@link McpSchema.CallToolRequest} 并同步 {@code callTool}，
 * 再把 {@link McpSchema.CallToolResult} 的 TextContent 拼成字符串。
 * {@code isError=true} 抛 {@link BusinessException}（{@link RuntimeErrorCode#MCP_TOOL_ERROR}），
 * 由 DefaultToolInvoker 收成失败观测；传输异常包成 {@link ToolExecutionException}。
 * 本 record 不缓存调用结果。</p>
 *
 * @param mcpClient 同步 MCP 客户端
 * @param tool      MCP 工具描述，同时用于生成 ToolDefinition
 * @author alpha
 * @since 2026/9/11
 */
@Slf4j
public record McpToolCallback(McpSyncClient mcpClient, McpSchema.Tool tool) implements ToolCallback {

    /**
     * JSON 编解码：把 LLM 传来的 arguments 字符串解析成 Map，塞进 CallToolRequest
     */
    private static final JsonCodec CODEC = JsonCodecFactory.instance();

    /**
     * 暴露给 Spring AI / Runtime 的工具定义（名 + input schema）。
     *
     * @return ToolDefinition；名取自 MCP tool.name
     */
    @Override
    public ToolDefinition getToolDefinition() {
        return McpToolUtils.createToolDefinition(tool.name(), this.tool);
    }

    /**
     * 无 ToolContext 的调用入口；委托给带 context 的重载。
     *
     * @param toolCallInput 工具入参 JSON 字符串；空则下游按 "{}" 处理
     * @return 成功时的文本结果；失败走异常
     */
    @Override
    public String call(String toolCallInput) {
        return this.call(toolCallInput, null);
    }

    /**
     * 执行一次 MCP 工具调用并返回脱壳后的文本。
     * <p>
     * 成功：返回 content 拼接文本。工具层失败（{@code isError}）：抛 BusinessException。
     * 传输/其它异常：包 ToolExecutionException。ToolContext 当前未参与 MCP 请求组装（预留）。
     * </p>
     *
     * @param toolCallInput 工具入参 JSON；空白则用 "{}"
     * @param toolContext   Spring AI 工具上下文；可空，本实现暂不使用
     * @return 脱壳后的文本结果
     */
    @Override
    public String call(String toolCallInput, @Nullable ToolContext toolContext) {
        // 空入参按 {} 发给远端，避免 schema 解析失败
        if (Strings.isBlank(toolCallInput)) {
            toolCallInput = "{}";
        }

        McpSchema.CallToolResult response;
        try {
            var request = McpSchema.CallToolRequest
                .builder(this.tool.name())
                .arguments(CODEC.str2map(toolCallInput))
                .build();

            response = this.mcpClient.callTool(request);
        } catch (ToolExecutionException ex) {
            // 已是执行异常则原样抛出，避免再包一层
            throw ex;
        } catch (Exception ex) {
            log.warn("[MCP] call failed tool={} cause={}", this.tool.name(), ex.toString());
            throw new ToolExecutionException(this.getToolDefinition(), ex);
        }

        String result = unwrapContent(response);
        if (Objects.nonNull(response.isError()) && response.isError()) {
            log.warn("[MCP] tool error tool={}", this.tool.name());

            // 协议层失败不重试，交给 Invoker 转失败观测
            throw new BusinessException(RuntimeErrorCode.MCP_TOOL_ERROR, result);
        }

        return result;
    }

    /**
     * 从 CallToolResult 抽出全部 TextContent 并拼接；非文本块跳过。
     *
     * @param response MCP callTool 返回体
     * @return 拼接后的文本；无内容则为空串
     */
    static String unwrapContent(McpSchema.CallToolResult response) {
        List<McpSchema.Content> contents = response.content();
        // 无 content 当空结果，不把协议失败误判成有正文
        if (Collections.isEmpty(contents)) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        for (McpSchema.Content c : contents) {
            // 非文本块跳过，MCP 图像等不进工具观测文本
            if (!(c instanceof McpSchema.TextContent _c)) {
                continue;
            }

            String text = _c.text();
            if (Strings.isBlank(text)) {
                continue;
            }

            result.append(text);
        }

        return result.toString();
    }
}
