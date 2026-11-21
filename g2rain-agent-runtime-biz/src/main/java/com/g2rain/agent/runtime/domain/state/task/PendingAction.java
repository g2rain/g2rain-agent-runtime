package com.g2rain.agent.runtime.domain.state.task;


import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 待确认的工具动作快照。
 * <p>
 * 典型场景：{@link CheckpointType#APPROVAL} 记下工具名与参数，用户
 * {@link ResumeAction#CONFIRM} 后再派发。
 * </p>
 * <p>恢复流程必须先校验 {@code argsHash} 与当前参数一致，再决定是否允许继续；该 record
 * 本身不会执行工具，也不会因用户确认而自动改变状态。</p>
 *
 * @param toolName 待执行的工具名
 * @param args     工具参数 JSON 对象
 * @param argsHash 业务入参摘要；确认执行时须与现参一致
 * @param notices  附加检查给出的警告说明，随确认内容展示给用户；没有为空列表
 * @author alpha
 * @since 2026/9/9
 */
public record PendingAction(String toolName, JsonNode args, @Nullable String argsHash, List<String> notices) {

    /**
     * JSON 编解码。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    public PendingAction {
        notices = List.copyOf(notices);
    }

    /**
     * 创建尚未计算参数摘要、没有警告的待确认动作。
     *
     * @param toolName 工具名
     * @param args     工具参数 JSON 对象
     */
    public PendingAction(String toolName, JsonNode args) {
        this(toolName, args, null, List.of());
    }

    /**
     * 从节点便签值还原待确认动作。
     *
     * <p>同一轮内便签保存的是本类型；跨轮水合后为通用结构，按 JSON 还原。</p>
     *
     * @param raw 便签值；可空
     * @return 待确认动作；便签为空时为 {@code null}
     */
    public static @Nullable PendingAction from(@Nullable Object raw) {
        return switch (raw) {
            case null -> null;
            case PendingAction typed -> typed;
            default -> JSON.convert(raw, PendingAction.class);
        };
    }
}
