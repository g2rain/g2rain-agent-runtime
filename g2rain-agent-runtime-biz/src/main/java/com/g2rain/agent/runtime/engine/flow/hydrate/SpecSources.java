package com.g2rain.agent.runtime.engine.flow.hydrate;

import com.g2rain.agent.runtime.spi.SpecSource;
import com.g2rain.agent.runtime.spi.SpecSourceType;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 动作语义来源表：按来源类型找 {@link SpecSource} 实现。
 *
 * <p>启动时建表：同一类型重复注册，或实现声明 {@link SpecSourceType#CONTRACT}，均启动失败。</p>
 *
 * <pre>{@code
 * SpecSource source = specSources.of(SpecSourceType.ONTOLOGY); // 没有对应实现时为 null
 * }</pre>
 *
 * @author alpha
 * @since 2026/11/21
 */
@Component
public class SpecSources {

    /**
     * 来源类型 → 实现。
     */
    private final Map<SpecSourceType, SpecSource> byType = new EnumMap<>(SpecSourceType.class);

    /**
     * 按类型登记全部实现。
     *
     * @param sources 动作语义来源实现；没有实现时为空列表
     * @throws IllegalStateException 类型为 {@link SpecSourceType#CONTRACT}，或同一类型重复注册
     */
    public SpecSources(List<SpecSource> sources) {
        for (SpecSource source : sources) {
            SpecSourceType type = source.type();
            if (type == SpecSourceType.CONTRACT) {
                throw new IllegalStateException("spec source type reserved by runtime: " + type);
            }

            SpecSource existing = byType.putIfAbsent(type, source);
            if (Objects.nonNull(existing)) {
                throw new IllegalStateException("duplicate spec source type: " + type);
            }
        }
    }

    /**
     * 取某类型的实现。
     *
     * @param type 来源类型
     * @return 实现；没有时为 {@code null}
     */
    public @Nullable SpecSource of(SpecSourceType type) {
        return byType.get(type);
    }
}
