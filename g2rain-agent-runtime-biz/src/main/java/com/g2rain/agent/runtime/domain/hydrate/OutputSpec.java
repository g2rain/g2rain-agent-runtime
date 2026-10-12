package com.g2rain.agent.runtime.domain.hydrate;

import com.g2rain.agent.runtime.domain.state.SourceType;
import com.g2rain.common.utils.Collections;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 节点输出的形态、来源和共享规则。
 *
 * <p>输出既可作为下游输入，也可按共享范围沉淀到任务或会话；required、type 和 minItems
 * 共同参与节点完成判定。默认不接受 MODEL 来源，避免输出在没有事实依据时被补写。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Getter
@Setter
@NoArgsConstructor
public class OutputSpec {

    /**
     * 形态；缺省 VALUE。
     */
    private @Nullable OutputType type;

    /**
     * LIST 输出每行允许保留的字段名；当类型为 LIST 时由发布校验要求非空。
     */
    private List<String> fields = new ArrayList<>();

    /**
     * LIST 最少行数；缺省 1。
     */
    private @Nullable Integer minItems;

    /**
     * 填写提示。
     */
    private @Nullable String hint;

    /**
     * 完成判定是否必须存在；缺省 true。
     */
    private @Nullable Boolean required;

    /**
     * 共享范围。
     */
    private @Nullable ShareScope share;

    /**
     * 敏感值只存 handle。
     */
    private boolean sensitive;

    /**
     * 实体沉淀。
     */
    private @Nullable EntitySpec entity;

    /**
     * 允许的来源类型；空=默认除 {@link SourceType#MODEL} 外均可。
     */
    private List<SourceType> sources = new ArrayList<>();

    /**
     * 契约常量；{@link SourceType#CONTRACT} 时值须与此相等。
     */
    private @Nullable JsonNode constant;

    /**
     * @return 缺省 VALUE
     */
    public OutputType typeOrDefault() {
        return Objects.requireNonNullElse(type, OutputType.VALUE);
    }

    /**
     * 判断该来源是否能填充当前输出。
     *
     * <p>空的 {@code sources} 表示允许受信来源但拒绝 {@link SourceType#MODEL}；显式列表则
     * 只允许列表中的类型。</p>
     *
     * @param type 待判断来源；可空
     * @return 允许写入该输出来源时为 {@code true}
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean allows(@Nullable SourceType type) {
        // 默认拒绝 MODEL，避免无事实依据的输出补写
        if (Objects.isNull(type) || type == SourceType.MODEL) {
            return false;
        }

        if (Collections.isEmpty(sources)) {
            return true;
        }

        return sources.contains(type);
    }

    /**
     * @return 缺省 1
     */
    public int minItemsOrDefault() {
        return Objects.requireNonNullElse(minItems, 1);
    }

    /**
     * @return 未配置时默认必填
     */
    public boolean isRequired() {
        return Objects.requireNonNullElse(required, true);
    }
}
