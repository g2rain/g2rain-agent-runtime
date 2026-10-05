package com.g2rain.agent.runtime.domain.state.task;


import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * RepairDirective（补参指令）：挂在 NodeSpace.repair 上的「须先追问 / 先调工具 / 平台缺上下文 / 溯源拒」义务。
 * <p>
 * 每个节点同一时刻最多一条；由参数契约拒调时写入。
 * </p>
 *
 * @param type           指令类型
 * @param expectedFields 须补齐的字段名（追问类常用）
 * @param toolName       须先调用的工具名；可空
 * @param message        给人/模型看的说明；可空则用默认文案
 * @param startRound     义务起始轮次，用于超轮熔断
 * @param errorCode      错误码；过期确认等只记码不抛异常
 * @author alpha
 * @since 2026/8/21
 */
public record RepairDirective(RepairDirectiveType type, List<String> expectedFields, @Nullable String toolName,
                              @Nullable String message, int startRound, @Nullable String errorCode) {

    public RepairDirective {
        // 空列表与调用方可变列表都收成不可变，避免执行期改义务字段
        expectedFields = Collections.isEmpty(expectedFields) ? List.of() : List.copyOf(expectedFields);
    }

    /**
     * 须追问用户补齐字段。
     *
     * @param fields  字段名列表
     * @param message 说明；可空
     * @param round   起始轮次
     * @return 追问义务
     */
    public static RepairDirective askUser(List<String> fields, @Nullable String message, int round) {
        return new RepairDirective(RepairDirectiveType.ASK_USER_REQUIRED, fields, null, message, round, null);
    }

    /**
     * 须先调用指定工具。
     *
     * @param toolName 工具名
     * @param message  说明；可空
     * @param round    起始轮次
     * @return 先调工具义务
     */
    public static RepairDirective callTool(String toolName, @Nullable String message, int round) {
        return new RepairDirective(RepairDirectiveType.CALL_TOOL_REQUIRED, List.of(), toolName, message, round, null);
    }

    /**
     * 溯源被拒。
     *
     * @param message 说明；可空
     * @param round   起始轮次
     * @return 溯源拒绝义务
     */
    public static RepairDirective reject(@Nullable String message, int round) {
        return new RepairDirective(RepairDirectiveType.PROVENANCE_REJECT, List.of(), null, message, round, null);
    }

    /**
     * 平台上下文缺失。
     *
     * @param message 说明；可空
     * @param round   起始轮次
     * @return 平台缺上下文义务
     */
    public static RepairDirective platformMissing(@Nullable String message, int round) {
        return new RepairDirective(RepairDirectiveType.PLATFORM_CONTEXT_MISSING, List.of(), null, message, round, null);
    }

    /**
     * 转成喂给模型的提示文案（有自定义 message 优先）。
     *
     * @return 提示字符串
     */
    public String toPromptMessage() {
        // 契约已写明说明时原样给模型，避免覆盖运营配置
        if (Strings.isNotBlank(message)) {
            return message.trim();
        }

        return switch (type) {
            case ASK_USER_REQUIRED -> ModelHint.REPAIR_ASK_USER.text("fields", String.join(",", expectedFields));
            case CALL_TOOL_REQUIRED -> ModelHint.REPAIR_CALL_TOOL.text("tool", Objects.toString(toolName, ""));
            case PLATFORM_CONTEXT_MISSING -> ModelHint.REPAIR_PLATFORM_MISSING.text();
            case PROVENANCE_REJECT -> ModelHint.REPAIR_PROVENANCE_REJECT.text();
        };
    }
}
