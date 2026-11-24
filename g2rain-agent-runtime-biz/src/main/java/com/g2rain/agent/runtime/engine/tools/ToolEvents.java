package com.g2rain.agent.runtime.engine.tools;


import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.state.progress.StepStage;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.Outcome;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.event.StepEvents;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import com.g2rain.agent.runtime.spec.ToolSpec;
import com.g2rain.agent.runtime.spi.Notice;
import com.g2rain.agent.runtime.support.RequestLocale;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.agent.runtime.support.mcp.McpToolCallback;
import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCallback;
import com.g2rain.agent.runtime.support.tool.CapabilityToolCallback;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具调用的执行步骤：开始时开启 TOOL 步骤，结束时按观测结果收尾。
 *
 * <p>每次工具调用（含内置工具）都推送步骤。内置工具标题取自注解配置的 {@link RuntimeMessage}；
 * 委派标题带目标技能名。业务工具标题优先契约 label，其次 MCP title，都没有时用通用的「调用工具」，
 * 不把内部工具名透给客户端；参数摘要只进排查信息。</p>
 *
 * @author alpha
 * @since 2026/8/27
 */
@Component
@RequiredArgsConstructor
public class ToolEvents {

    /**
     * JSON 编解码：读委派入参中的技能键。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 委派工具入参中的技能键字段。
     */
    private static final String DELEGATE_SKILL_ARG = "skillKey";

    /**
     * 警告状态步骤说明中多条警告的分隔符。
     */
    private static final String NOTICE_DELIMITER = "；";

    /**
     * 执行步骤的记录与推送。
     */
    private final StepEvents stepEvents;

    /**
     * 开启工具步骤；内置与业务工具一律推送。
     *
     * @param request 本次工具请求
     * @return 步骤标识；未绑定本轮时由 {@link StepEvents} 决定是否为空
     */
    public @Nullable String started(ToolInvokeRequest request) {
        if (request.toolCallback() instanceof BuiltinToolCallback builtin) {
            return stepEvents.start(StepStage.TOOL, builtinTitleOf(builtin, request), request.context().getNodeId(),
                request.context().scope().rounds());
        }

        String label = labelOf(request.contract(), request.toolCallback());
        String title = Objects.isNull(label) ? RuntimeMessage.STEP_TOOL_DEFAULT.text() : RuntimeMessage.STEP_TOOL.text("name", label);
        return stepEvents.start(StepStage.TOOL, title, request.context().getNodeId(), request.context().scope().rounds());
    }

    /**
     * 按观测结果收尾工具步骤。
     *
     * @param stepId      工具步骤；为空时跳过
     * @param request     本次工具请求
     * @param observation 工具观测
     * @param args        参数摘要；可空，只进排查信息
     */
    public void finished(@Nullable String stepId, ToolInvokeRequest request, Observation observation, @Nullable String args) {
        if (Objects.isNull(stepId)) {
            return;
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        if (Strings.isNotBlank(args)) {
            extra.put("args", argsValue(args));
        }

        // 能力工具：按观测结论标成功或失败，不走内置「只记完成」
        if (ToolCallbacks.is(request.toolCallback(), BuiltinToolAction.CAPABILITY)) {
            if (observation.outcome() == Outcome.SUCCESS) {
                stepEvents.done(stepId, RuntimeMessage.STEP_TOOL_OK.text(), extra);
                return;
            }

            extra.put("error", observation.message());
            String errorCode = Objects.isNull(observation.errorCode()) ? null : observation.errorCode().code();
            stepEvents.fail(stepId, observation.text(), errorCode, extra);
            return;
        }

        // 内置工具：完成被门槛拒绝记为失败并带原因，其余只记完成，不加"成功"说明
        if (ToolCallbacks.isBuiltin(request.toolCallback())) {
            String rejected = request.context().getFinishRejectedReason();
            if (Strings.isNotBlank(rejected)) {
                stepEvents.fail(stepId, rejected, null, extra);
                return;
            }
            stepEvents.done(stepId, null, extra);
            return;
        }
        if (observation.outcome() == Outcome.SUCCESS) {
            List<Notice> notices = request.notices();
            if (notices.isEmpty()) {
                stepEvents.done(stepId, RuntimeMessage.STEP_TOOL_OK.text(), extra);
                return;
            }

            // 放行但有警告：标为警告状态，说明逐条列出
            extra.put("notices", notices);
            stepEvents.warn(stepId, String.join(NOTICE_DELIMITER, notices.stream().map(Notice::message).toList()), extra);
            return;
        }

        extra.put("error", observation.message());
        String errorCode = Objects.isNull(observation.errorCode()) ? null : observation.errorCode().code();
        stepEvents.fail(stepId, observation.text(), errorCode, extra);
    }

    /**
     * 内置工具的步骤标题：取回调上的文案；委派带上目标技能名。
     *
     * @param builtin 内置工具回调
     * @param request 本次工具请求
     * @return 步骤标题
     */
    private static String builtinTitleOf(BuiltinToolCallback builtin, ToolInvokeRequest request) {
        RuntimeMessage step = builtin.step();
        if (builtin.action() == BuiltinToolAction.CAPABILITY) {
            return step.text("name", capabilityLabel(builtin));
        }

        if (builtin.action() != BuiltinToolAction.DELEGATE) {
            return step.text();
        }

        return step.text("skill", skillTitleOf(request.context(), request.args()));
    }

    /**
     * 能力工具展示名：取 {@link CapabilityToolCallback} 在请求语言下的 label，缺省 {@code zh-CN}。
     *
     * @param builtin 装饰后的能力回调
     * @return 展示名
     */
    private static String capabilityLabel(BuiltinToolCallback builtin) {
        if (builtin.delegate() instanceof CapabilityToolCallback capability) {
            return capability.label(RequestLocale.tag());
        }

        return builtin.getToolDefinition().name();
    }

    /**
     * 委派目标技能的标题：按入参技能键在本节点可见技能中查找，找不到时用技能键。
     *
     * @param ctx  节点执行现场
     * @param args 委派工具入参
     * @return 技能标题；入参不可读时为空串
     */
    private static String skillTitleOf(ToolExecutionContext ctx, JsonNode args) {
        JsonNode node = JSON.lookupNode(args, DELEGATE_SKILL_ARG);
        String skillKey = JSON.hasValue(node) ? JSON.asString(node) : null;
        if (Strings.isBlank(skillKey)) {
            return "";
        }

        for (Skill skill : ctx.getSkills()) {
            if (skillKey.equals(skill.skillKey())) {
                return skill.title();
            }
        }

        return skillKey;
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

    /**
     * 参数摘要按 JSON 写入排查信息，避免二次编码；无法解析时保留原文。
     *
     * @param args 参数摘要
     * @return JSON 节点或原文
     */
    private static Object argsValue(String args) {
        try {
            JsonNode node = JSON.str2node(args);
            return Objects.isNull(node) ? args : node;
        } catch (RuntimeException _) {
            return args;
        }
    }
}
