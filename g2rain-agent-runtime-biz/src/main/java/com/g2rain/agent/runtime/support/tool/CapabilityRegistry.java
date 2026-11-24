package com.g2rain.agent.runtime.support.tool;

import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.guard.EvidenceReader;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import com.g2rain.agent.runtime.spi.Capability;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 能力工具注册表：收集容器中全部 {@link Capability}，校验名称后装饰为可调用回调。
 *
 * <p>可为空列表；名称格式非法、与内置工具同名、两个能力同名时启动失败。</p>
 *
 * @author alpha
 * @since 2026/11/24
 */
public final class CapabilityRegistry {

    /**
     * 工具名：小写字母、数字、下划线，2–64 位。
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]{2,64}$");

    /**
     * 禁止的名称前缀：内置工具。
     */
    private static final String PREFIX_BUILTIN = "builtin_";

    /**
     * 禁止的名称前缀：原生保留。
     */
    private static final String PREFIX_NATIVE = "native_";

    /**
     * 装饰后的回调（类别 CAPABILITY），构造后只读。
     */
    private final List<ToolCallback> callbacks;

    /**
     * 收集并校验能力 Bean。
     *
     * @param capabilities   能力实现；可为空列表
     * @param builtinTools   内置工具回调提供者（用于同名冲突）
     * @param evidenceReader 证据读取器
     * @param stateFacade    状态门面
     * @throws IllegalStateException 名称非法或冲突
     */
    public CapabilityRegistry(List<Capability> capabilities, BuiltinToolCallbackProvider builtinTools,
                              EvidenceReader evidenceReader, RuntimeStateFacade stateFacade) {
        Map<String, Capability> byName = new LinkedHashMap<>();
        List<ToolCallback> decorated = new ArrayList<>();
        for (Capability capability : capabilities) {
            String name = capability.spec().name();
            String source = ClassUtils.getUserClass(capability).getName();
            validateName(name, source);
            if (builtinName(builtinTools, name)) {
                throw new IllegalStateException(
                    "capability name conflicts with builtin tool: '" + name + "' from " + source);
            }

            Capability existing = byName.putIfAbsent(name, capability);
            if (Objects.nonNull(existing)) {
                throw new IllegalStateException(
                    "duplicate capability name '" + name + "': "
                        + ClassUtils.getUserClass(existing).getName() + " and " + source);
            }

            CapabilityToolCallback callback = new CapabilityToolCallback(capability, evidenceReader, stateFacade);
            decorated.add(new BuiltinToolCallback(
                callback, BuiltinToolCategory.CAPABILITY, BuiltinToolAction.CAPABILITY, RuntimeMessage.STEP_TOOL));
        }

        this.callbacks = List.copyOf(decorated);
    }

    /**
     * 全部已装饰的能力工具回调；没有能力时为空列表。
     *
     * @return 回调列表
     */
    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    /**
     * 校验名称格式。
     *
     * @param name   工具名
     * @param source 来源类名
     */
    private static void validateName(@Nullable String name, String source) {
        if (Strings.isBlank(name) || !NAME_PATTERN.matcher(name).matches()
            || name.startsWith(PREFIX_BUILTIN) || name.startsWith(PREFIX_NATIVE)) {
            throw new IllegalStateException("invalid capability name '" + name + "' from " + source);
        }
    }

    /**
     * 是否与某个内置工具同名。
     *
     * @param builtinTools 内置工具提供者
     * @param name         能力名
     * @return 冲突时为 {@code true}
     */
    private static boolean builtinName(BuiltinToolCallbackProvider builtinTools, String name) {
        for (ToolCallback callback : builtinTools.getToolCallbacks()) {
            if (name.equals(callback.getToolDefinition().name())) {
                return true;
            }
        }

        return false;
    }
}
