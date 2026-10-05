package com.g2rain.agent.runtime.support.mcp;


import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.lang.reflect.UndeclaredThrowableException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 按本轮允许的工具名，从 MCP 同步客户端拉清单并建成 ToolCallback。
 *
 * <p>{@code toolNames} 空则返回空数组。{@code listTools} 遇超时或断连按 {@code maxRetries} 重试；
 * {@code callTool} 不在此重试。拉清单失败且非业务异常时打成依赖不可用。
 * 本类不缓存清单，每次 {@link #getToolCallbacks()} 重新拉取。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
public final class McpToolCallbackProvider implements ToolCallbackProvider {

    /**
     * 传给 MCP listTools 的元数据键：本轮允许的工具名集合。
     */
    private static final String META_KEY = "com.g2rain/allowed_tools";

    /**
     * 同步 MCP 客户端，构造后只读。
     */
    private final McpSyncClient mcpSyncClient;

    /**
     * 本轮允许暴露的工具名；空或 null 则不暴露任何回调。
     */
    @Nullable
    private final Set<String> toolNames;

    /**
     * listTools 建连失败的额外重试次数，不含首次。
     */
    private final int maxRetries;

    /**
     * @param mcpSyncClient 同步 MCP 客户端
     * @param toolNames     允许的工具名集合；可空
     * @param maxRetries    listTools 额外重试次数；≤0 则只打一次
     */
    public McpToolCallbackProvider(McpSyncClient mcpSyncClient, @Nullable Set<String> toolNames, int maxRetries) {
        this.mcpSyncClient = mcpSyncClient;
        this.toolNames = toolNames;
        this.maxRetries = Math.max(0, maxRetries);
    }

    /**
     * 列出 MCP 工具并包装为 Spring AI ToolCallback；仅包含有名工具。
     *
     * @return 工具回调数组；无允许名时为空数组
     * @throws BusinessException 拉清单失败或依赖不可用
     */
    @Override
    public ToolCallback[] getToolCallbacks() {
        // 本轮没有允许名则不打 MCP，避免拉全量清单
        if (Collections.isEmpty(toolNames)) {
            return new ToolCallback[0];
        }

        try {
            Map<String, Object> meta = Map.of(META_KEY, toolNames);
            return Optional.of(listToolsWithRetry(meta))
                .map(McpSchema.ListToolsResult::tools).orElseGet(List::of)
                .stream().filter(Objects::nonNull)
                .filter(tool -> Strings.isNotBlank(tool.name()))
                .map(tool -> new McpToolCallback(mcpSyncClient, tool))
                .toArray(ToolCallback[]::new);
        } catch (BusinessException ex) {
            // 业务错误不改成依赖不可用，保留原错误码
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE,
                ex, "MCP Registry"
            );
        }
    }

    /**
     * 调用 {@code listTools}；超时/断连按 {@link #maxRetries} 重试。
     *
     * @param meta 传给远端的过滤元数据
     * @return 清单结果；客户端可返回 null
     */
    private McpSchema.ListToolsResult listToolsWithRetry(Map<String, Object> meta) {
        Exception lastAccessError = null;
        int attempts = 1 + maxRetries;
        for (int i = 0; i < attempts; i++) {
            try {
                return mcpSyncClient.listTools(null, meta);
            } catch (BusinessException ex) {
                throw ex;
            } catch (Exception ex) {
                // 非超时/断连立即失败；只有建连类错误才计入重试
                if (!isConnectFailure(ex)) {
                    throw unwrap(ex);
                }
                lastAccessError = ex;
                log.warn("MCP listTools access failed, will retry ({}/{}): {}",
                    i + 1, maxRetries, ex.getMessage()
                );
            }
        }

        if (Objects.isNull(lastAccessError)) {
            lastAccessError = new IllegalStateException("retries exhausted");
        }
        throw new BusinessException(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE,
            lastAccessError, "MCP Registry"
        );
    }

    /**
     * 把受检异常拆成运行时；已是 RuntimeException 则原样返回。
     *
     * @param ex 原始异常
     * @return 运行时异常
     */
    private static RuntimeException unwrap(Exception ex) {
        if (ex instanceof RuntimeException exception) {
            return exception;
        }

        return new UndeclaredThrowableException(ex);
    }

    /**
     * 沿 cause 链判断是否为超时 / 连接失败。
     *
     * @param ex 异常
     * @return 是否连接类失败
     */
    private static boolean isConnectFailure(Throwable ex) {
        Throwable t = ex;
        while (Objects.nonNull(t)) {
            if (t instanceof HttpTimeoutException
                || t instanceof TimeoutException
                || t instanceof SocketTimeoutException
                || t instanceof ConnectException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
