package com.g2rain.agent.runtime.support.tool;


import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import com.g2rain.agent.runtime.spi.ToolKind;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Objects;

/**
 * 带元数据的内置工具回调：装饰原始 {@link ToolCallback}，附带类别、动作与步骤标题。
 *
 * <p>定义、元数据与 call 全部委托 {@code delegate}；额外暴露 category/action/step。
 * call 前会把「Schema 是数组、模型传成 JSON 数组字符串」的顶层入参还原为数组，见 {@link RuntimeUtils#normalizeArrayArgs}。
 * 例：写节点产出 → category=NODE、action=OUTPUT、step=STEP_OUTPUT。</p>
 *
 * @param delegate 被装饰的 Spring AI 工具回调
 * @param category 工具类别；来自 {@link BuiltinToolKind}，未标注时为空
 * @param action   工具动作；来自 {@link BuiltinToolKind}，未标注时为空
 * @param step     步骤标题文案；来自 {@link BuiltinToolKind#step()}，未标注时为缺省「调用工具」
 * @author alpha
 * @since 2026/8/29
 */
public record BuiltinToolCallback(ToolCallback delegate, @Nullable BuiltinToolCategory category,
                                  @Nullable BuiltinToolAction action, RuntimeMessage step) implements ToolCallback {

    /**
     * 由 {@link BuiltinToolKind} 展开类别、动作与步骤标题的便捷构造。
     * <p>
     * kind 为空时 category/action 均为 {@code null}，step 为 {@link RuntimeMessage#STEP_TOOL_DEFAULT}。
     *
     * @param delegate 原始工具回调
     * @param kind     方法上的种类注解；可空
     */
    public BuiltinToolCallback(ToolCallback delegate, @Nullable BuiltinToolKind kind) {
        this(
            delegate,
            Objects.isNull(kind) ? null : kind.category(),
            Objects.isNull(kind) ? null : kind.action(),
            Objects.isNull(kind) ? RuntimeMessage.STEP_TOOL_DEFAULT : kind.step()
        );
    }

    /**
     * 工具种类：能力工具取其 {@link com.g2rain.agent.runtime.spi.CapabilitySpec#kind()}，其余按类别对应。
     *
     * @return 工具种类；未标类别时为空
     */
    public @Nullable ToolKind toolKind() {
        if (delegate instanceof CapabilityToolCallback capability) {
            return capability.spec().kind();
        }

        if (Objects.isNull(category)) {
            return null;
        }

        return switch (category) {
            case CONTROL -> ToolKind.CONTROL;
            case ENV -> ToolKind.ENV;
            case SESSION -> ToolKind.SESSION;
            case NODE -> ToolKind.NODE;
            // 能力工具的种类由 CapabilitySpec 声明，未包装能力回调时无从得知
            case CAPABILITY -> null;
        };
    }

    /**
     * 转发工具定义（名称、描述、入参 JSON Schema）。
     *
     * @return 委托方的 ToolDefinition
     */
    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    /**
     * 转发工具元数据（如是否直接返回结果等框架标记）。
     *
     * @return 委托方的 ToolMetadata
     */
    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    /**
     * 无 ToolContext 的调用：数组入参归一化后转发给委托方。
     *
     * @param toolInput 模型给出的工具入参 JSON 字符串
     * @return 工具执行结果字符串
     */
    @Override
    public String call(String toolInput) {
        return delegate.call(normalize(toolInput));
    }

    /**
     * 带 ToolContext 的调用：数组入参归一化后转发给委托方（内置工具常从此取会话/任务上下文）。
     *
     * @param toolInput   工具入参 JSON
     * @param toolContext Spring AI 工具上下文；可空
     * @return 工具执行结果字符串
     */
    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        return delegate.call(normalize(toolInput), toolContext);
    }

    /**
     * 按本工具的入参 Schema 归一化数组参数。
     *
     * @param toolInput 原始入参 JSON
     * @return 归一化后的入参
     */
    private String normalize(String toolInput) {
        return RuntimeUtils.normalizeArrayArgs(toolInput, getToolDefinition().inputSchema());
    }
}
