package com.g2rain.agent.runtime.domain.hydrate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * 节点开始时需要绑定的输入。
 *
 * <p>外层 Map 的 key 是输入名。省略 {@code from} 时按最近上游同名 output 绑定；
 * 必填缺值会挡住节点进入执行。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Getter
@Setter
@NoArgsConstructor
public class InputSpec {

    /**
     * 取值表达式；省略则按最近上游同名 output 绑定。
     */
    private @Nullable String from;

    /**
     * 是否必填；{@code null} 视为必填。
     */
    private @Nullable Boolean required;

    /**
     * 能提供该值的 skillKey。
     */
    private @Nullable String provider;

    /**
     * @return 未配置时默认必填
     */
    public boolean isRequired() {
        return Objects.requireNonNullElse(required, true);
    }
}
