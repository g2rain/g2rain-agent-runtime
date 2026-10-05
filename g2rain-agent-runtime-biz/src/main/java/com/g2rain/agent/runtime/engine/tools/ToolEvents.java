package com.g2rain.agent.runtime.engine.tools;


import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.agent.runtime.event.EventPublisher;
import com.g2rain.agent.runtime.event.TurnEvents;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.agent.runtime.support.mcp.McpToolCallback;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 业务工具开始调用时的过程提示。
 *
 * <p>内置工具不发提示。文案优先契约 label，其次 MCP title，都没有时用通用的「正在处理」，
 * 不把内部工具名透给客户端。</p>
 *
 * @author alpha
 * @since 2026/8/27
 */
@Component
@RequiredArgsConstructor
public class ToolEvents {

    /**
     * 本轮事件发布器。
     */
    private final EventPublisher eventPublisher;

    /**
     * 发布工具开始的过程提示；内置工具直接返回。
     *
     * @param request 本次工具请求
     */
    public void started(ToolInvokeRequest request) {
        if (ToolCallbacks.isBuiltin(request.toolCallback())) {
            return;
        }

        String text = titleOf(request.contract(), request.toolCallback());
        eventPublisher.publish(TurnEvents.progress(request.context().getFrame().turn(), text));
    }

    /**
     * 解析过程提示文案。
     *
     * @param contract 工具契约；可空
     * @param callback 实际回调，用于读取 MCP title
     * @return 非空文案
     */
    private static String titleOf(@Nullable ToolSpec contract, ToolCallback callback) {
        if (Objects.nonNull(contract) && Strings.isNotBlank(contract.getLabel())) {
            return contract.getLabel();
        }

        if (callback instanceof McpToolCallback mcp && Strings.isNotBlank(mcp.tool().title())) {
            return mcp.tool().title();
        }

        return RuntimeMessage.STATUS_WORKING.text();
    }
}
