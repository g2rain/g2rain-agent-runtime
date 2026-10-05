package com.g2rain.agent.runtime.domain.hydrate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * 写工具结果不确定时的只读回查配置。
 *
 * <p>UNKNOWN 证据恢复时先调 {@link #tool}（必须是无副作用工具），用 {@link #args} 绑定
 * {@code call.*} 等来源；没有本配置时不得把不确定结果当成成功。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Getter
@Setter
@NoArgsConstructor
public class VerifySpec {

    /**
     * 回查工具注册名；副作用必须是 NONE。
     */
    private String tool;

    /**
     * 回查参数；{@code from} 可引用 {@code call.*} 指向原写调用。
     */
    private Map<String, ArgSpec> args = new HashMap<>();
}
