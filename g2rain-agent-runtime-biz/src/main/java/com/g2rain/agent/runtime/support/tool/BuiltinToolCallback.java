package com.g2rain.agent.runtime.support.tool;


import com.g2rain.agent.runtime.i18n.RuntimeMessage;
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
     * 无 ToolContext 的调用：原样转发给委托方。
     *
     * @param toolInput 模型给出的工具入参 JSON 字符串
     * @return 工具执行结果字符串
     */
    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    /**
     * 带 ToolContext 的调用：原样转发给委托方（内置工具常从此取会话/任务上下文）。
     *
     * @param toolInput   工具入参 JSON
     * @param toolContext Spring AI 工具上下文；可空
     * @return 工具执行结果字符串
     */
    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }
}
