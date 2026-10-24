package com.g2rain.agent.runtime.domain.hydrate;

import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具在当前 Skill 中的执行契约。
 *
 * <p>外层 Map 的 key 是工具注册名称；本对象集中描述副作用、确认、幂等、参数、成功判定、
 * 错误判定和结果抽取规则。字段为空时由运行时的默认方法解释，但发布校验仍需检查 WRITE
 * 工具的必要成功条件。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Getter
@Setter
@NoArgsConstructor
public class ToolSpec {

    /**
     * 展示名。
     */
    private @Nullable String label;

    /**
     * 副作用；缺省 NONE。
     */
    private @Nullable SideEffect sideEffect;

    /**
     * 写工具是否须确认；WRITE 缺省 true。
     */
    private @Nullable Boolean confirm;

    /**
     * 幂等键取值路径；空则用全量 argsHash。
     */
    private List<String> idempotencyKey = new ArrayList<>();

    /**
     * UNKNOWN 结果的核查配置；没有配置时不能把不确定结果当作成功。
     */
    private @Nullable VerifySpec verify;

    /**
     * 参数路径到参数绑定契约的映射。
     */
    private Map<String, ArgSpec> args = new HashMap<>();

    /**
     * 业务成功条件。
     */
    private @Nullable SuccessSpec success;

    /**
     * 失败字段路径。
     */
    private @Nullable ErrorSpec error;

    /**
     * 抽取 / 判空 / 计数。
     */
    private List<ExtractSpec> extracts = new ArrayList<>();

    /**
     * 直接作答：响应中答复文本的路径。
     *
     * <p>业务成功且取到非空文本时，以该文本完成节点，不再回到模型；完成门槛未过或取不到文本时照常进入下一轮。
     * 只允许配在 NONE 工具上。</p>
     */
    private @Nullable String returnDirect;

    /**
     * @return 缺省 NONE
     */
    public SideEffect sideEffectOrDefault() {
        return Objects.requireNonNullElse(sideEffect, SideEffect.NONE);
    }

    /**
     * 判断当前工具是否需要用户确认。
     *
     * <p>显式配置优先；未配置时只有副作用为 WRITE 才默认需要确认，NONE 工具不会因为
     * 缺少字段而进入审批流程。</p>
     *
     * @return 需要审批挂起时为 {@code true}
     */
    public boolean requiresConfirm() {
        // 显式配置优先；未配置时只有 WRITE 默认进审批
        if (Objects.nonNull(confirm)) {
            return confirm;
        }

        return sideEffectOrDefault() == SideEffect.WRITE;
    }
}
