package com.g2rain.agent.runtime.engine.flow.react;

import com.g2rain.agent.runtime.domain.hydrate.ArgSpec;
import com.g2rain.agent.runtime.domain.hydrate.MissingSpec;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Objects;

/**
 * 工具目录投影：把本轮可见工具与技能契约中的工具声明转成 {@code tool_catalog}。
 *
 * @author alpha
 * @since 2026/10/12
 */
final class ToolCatalogProjector {

    /**
     * 静态投影工具，禁止实例化。
     */
    private ToolCatalogProjector() {

    }

    /**
     * 组装 {@code tool_catalog}：本节点可见工具。
     *
     * @param callbacks 本轮可见工具
     * @param contract  技能契约；可空
     * @return 工具列表；没有可投影工具时为 {@code null}
     */
    static @Nullable List<ReactProjection.ToolItemView> of(List<ToolCallback> callbacks, @Nullable SkillContract contract) {
        List<ReactProjection.ToolItemView> items = callbacks.stream()
            .map(callback -> toolItem(callback, contract))
            .filter(Objects::nonNull)
            .map(Objects::requireNonNull)
            .toList();

        return items.isEmpty() ? null : items;
    }

    /**
     * 单个工具回调转投影项；非内置且契约无声明则省略。
     *
     * @param callback 工具回调
     * @param contract 技能契约；可空
     * @return 工具投影；不可见时为 {@code null}
     */
    private static ReactProjection.@Nullable ToolItemView toolItem(ToolCallback callback, @Nullable SkillContract contract) {
        String name = callback.getToolDefinition().name();
        boolean builtin = ToolCallbacks.isBuiltin(callback);
        ToolSpec spec = Objects.isNull(contract) ? null : contract.tool(name);
        if (!builtin && Objects.isNull(spec)) {
            return null;
        }

        List<ReactProjection.ArgItemView> args = Objects.isNull(spec) ? null : argItems(spec);
        return new ReactProjection.ToolItemView(
            name,
            builtin ? Boolean.TRUE : null,
            Objects.isNull(spec) ? null : Strings.blankToNull(spec.getLabel()),
            Objects.isNull(spec) ? null : spec.requiresConfirm(),
            args
        );
    }

    /**
     * 工具参数声明列表。
     *
     * @param spec 工具契约
     * @return 参数投影；无 args 时为 {@code null}
     */
    private static @Nullable List<ReactProjection.ArgItemView> argItems(ToolSpec spec) {
        if (spec.getArgs().isEmpty()) {
            return null;
        }

        return spec.getArgs().entrySet().stream()
            .map(entry -> argItem(entry.getKey(), entry.getValue()))
            .toList();
    }

    /**
     * 单个工具参数声明。
     *
     * @param path 参数路径
     * @param arg  参数声明
     * @return 参数投影
     */
    private static ReactProjection.ArgItemView argItem(String path, ArgSpec arg) {
        MissingSpec missing = arg.getMissing();
        return new ReactProjection.ArgItemView(
            path,
            Strings.blankToNull(arg.getLabel()),
            Objects.isNull(missing) ? null : missing.actionOrDefault().name(),
            Objects.isNull(missing) ? null : Strings.blankToNull(missing.getTool())
        );
    }
}
