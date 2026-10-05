package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.task.RepairDirective;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCallback;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCategory;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;

import java.util.Objects;
import java.util.Optional;

/**
 * 补参义务存取：在 {@link NodeScope#repair()} 上读写节点级 {@link RepairDirective}，并校验本轮工具是否合规。
 *
 * <p>每个节点同一时刻最多一条义务；存活轮数由 {@code react.repair-max-rounds} 约束。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class ArgRepairObligation {

    /**
     * 静态义务工具，禁止实例化。
     */
    private ArgRepairObligation() {

    }

    /**
     * 读取节点上挂着的补参指令。
     *
     * @param scope 当前节点作用域；可空
     * @return 指令；无则 {@code null}
     */
    public static @Nullable RepairDirective read(@Nullable NodeScope scope) {
        if (Objects.isNull(scope)) {
            return null;
        }

        return scope.repair();
    }

    /**
     * 把补参指令写入节点作用域。
     *
     * @param scope     当前节点作用域
     * @param directive 待挂上的补参指令
     */
    public static void save(NodeScope scope, RepairDirective directive) {
        scope.repair(directive);
    }

    /**
     * 清除节点上的补参指令。
     *
     * @param scope 当前节点作用域
     */
    public static void clear(NodeScope scope) {
        scope.clearRepair();
    }

    /**
     * 校验当前工具调用是否满足补参义务；不满足则返回拒调原因。
     *
     * @param obligation      当前义务；可空则放行
     * @param callback        工具回调；可空
     * @param toolName        工具名
     * @param currentRound    当前轮次
     * @param repairMaxRounds 补参义务存活轮数上限
     * @return 空=合规；有值=拒调文案
     */
    public static Optional<String> validate(@Nullable RepairDirective obligation, @Nullable ToolCallback callback,
                                            String toolName, int currentRound, int repairMaxRounds) {
        if (Objects.isNull(obligation) || Strings.isBlank(toolName)) {
            return Optional.empty();
        }

        // 挂太久：强制模型收口，避免死循环补参
        if (currentRound - obligation.startRound() >= repairMaxRounds) {
            return Optional.of(ModelHint.REPAIR_MAX_ROUNDS.text("rounds", String.valueOf(repairMaxRounds)));
        }

        String code = toolName.trim();
        return switch (obligation.type()) {
            case ASK_USER_REQUIRED, PROVENANCE_REJECT -> {
                // 只许追问 / 写便签 / 失败；其它业务工具拒调
                if (isAskOrStateWrite(callback) || isFail(callback)) {
                    yield Optional.empty();
                }
                yield Optional.of(obligation.toPromptMessage() + "\n" + ModelHint.REPAIR_ASK_ONLY.text());
            }
            case CALL_TOOL_REQUIRED -> {
                String required = obligation.toolName();
                // 调到指定工具则放行
                if (Strings.isNotBlank(required) && required.equalsIgnoreCase(code)) {
                    yield Optional.empty();
                }
                if (isAskOrStateWrite(callback) || isFail(callback)) {
                    yield Optional.empty();
                }
                yield Optional.of(obligation.toPromptMessage()
                    + "\n" + ModelHint.REPAIR_CALL_OR_FOLLOW_UP.text("tool", Objects.toString(required, "")));
            }
            case PLATFORM_CONTEXT_MISSING -> {
                // 平台缺上下文：只许失败收口
                if (isFail(callback)) {
                    yield Optional.empty();
                }
                yield Optional.of(obligation.toPromptMessage());
            }
        };
    }

    /**
     * 成功调用后是否清除补参义务。
     *
     * @param callback   工具回调；可空
     * @param toolName   工具名
     * @param obligation 当前义务
     * @return true=应 clear
     */
    public static boolean shouldClear(@Nullable ToolCallback callback, String toolName, RepairDirective obligation) {
        return switch (obligation.type()) {
            case ASK_USER_REQUIRED, PROVENANCE_REJECT -> isAskOrStateWrite(callback);
            case CALL_TOOL_REQUIRED -> Strings.isNotBlank(obligation.toolName())
                && Strings.isNotBlank(toolName)
                && obligation.toolName().equalsIgnoreCase(toolName.trim());
            case PLATFORM_CONTEXT_MISSING -> isFail(callback);
        };
    }

    /**
     * 追问或节点便签写入。
     *
     * @param callback 工具回调；可空
     * @return 是 ASK 或节点 WRITE 内置工具
     */
    private static boolean isAskOrStateWrite(@Nullable ToolCallback callback) {
        if (!(callback instanceof BuiltinToolCallback builtin)) {
            return false;
        }

        if (builtin.action() == BuiltinToolAction.ASK) {
            return true;
        }

        return (builtin.category() == BuiltinToolCategory.NODE) && builtin.action() == BuiltinToolAction.WRITE;
    }

    /**
     * 是否 node_fail 内置工具
     *
     * @param callback 工具回调；可空
     * @return 是 FAIL 内置工具
     */
    private static boolean isFail(@Nullable ToolCallback callback) {
        return callback instanceof BuiltinToolCallback builtin && builtin.action() == BuiltinToolAction.FAIL;
    }
}
