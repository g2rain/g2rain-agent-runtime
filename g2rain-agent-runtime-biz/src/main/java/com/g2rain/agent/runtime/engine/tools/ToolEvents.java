package com.g2rain.agent.runtime.engine.tools;


import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.state.progress.StepStage;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.Outcome;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.agent.runtime.event.StepEvents;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.agent.runtime.support.mcp.McpToolCallback;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 业务工具调用的执行步骤：开始时开启 TOOL 步骤，结束时按观测结果收尾。
 *
 * <p>内置工具不记步骤。标题优先契约 label，其次 MCP title，都没有时用通用的「调用工具」，
 * 不把内部工具名透给客户端；参数摘要只进排查信息。</p>
 *
 * @author alpha
 * @since 2026/8/27
 */
@Component
@RequiredArgsConstructor
public class ToolEvents {

    /**
     * 执行步骤的记录与推送。
     */
    private final StepEvents stepEvents;

    /**
     * 开启工具步骤；内置工具不记。
     *
     * @param request 本次工具请求
     * @return 步骤标识；内置工具或未绑定本轮时为空
     */
    public @Nullable String started(ToolInvokeRequest request) {
        if (ToolCallbacks.isBuiltin(request.toolCallback())) {
            return null;
        }

        String label = labelOf(request.contract(), request.toolCallback());
        String title = Objects.isNull(label) ? RuntimeMessage.STEP_TOOL_DEFAULT.text() : RuntimeMessage.STEP_TOOL.text("name", label);
        return stepEvents.start(StepStage.TOOL, title, request.context().getNodeId(), null);
    }

    /**
     * 按观测结果收尾工具步骤。
     *
     * @param stepId      工具步骤；为空时跳过
     * @param observation 工具观测
     * @param args        参数摘要；可空，只进排查信息
     */
    public void finished(@Nullable String stepId, Observation observation, @Nullable String args) {
        if (Objects.isNull(stepId)) {
            return;
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        if (Strings.isNotBlank(args)) {
            extra.put("args", args);
        }
        if (observation.outcome() == Outcome.SUCCESS) {
            stepEvents.done(stepId, RuntimeMessage.STEP_TOOL_OK.text(), extra);
            return;
        }

        extra.put("error", observation.message());
        String errorCode = Objects.isNull(observation.errorCode()) ? null : observation.errorCode().code();
        stepEvents.fail(stepId, observation.text(), errorCode, extra);
    }

    /**
     * 解析工具的展示名。
     *
     * @param contract 工具契约；可空
     * @param callback 实际回调，用于读取 MCP title
     * @return 展示名；都没有时为空
     */
    private static @Nullable String labelOf(@Nullable ToolSpec contract, ToolCallback callback) {
        if (Objects.nonNull(contract) && Strings.isNotBlank(contract.getLabel())) {
            return contract.getLabel();
        }

        if (callback instanceof McpToolCallback mcp && Strings.isNotBlank(mcp.tool().title())) {
            return mcp.tool().title();
        }

        return null;
    }
}
