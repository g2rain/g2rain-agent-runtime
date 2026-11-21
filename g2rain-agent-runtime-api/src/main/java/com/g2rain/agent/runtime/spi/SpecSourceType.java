package com.g2rain.agent.runtime.spi;

import org.jspecify.annotations.Nullable;

/**
 * 动作语义来源类型：契约 {@code source.type} 的取值。
 *
 * <p>{@link #CONTRACT} 由 Runtime 处理；其余类型由声明该类型的 {@link SpecSource} 实现提供。</p>
 *
 * @author alpha
 * @since 2026/11/21
 */
public enum SpecSourceType {

    /**
     * 契约自身：工具语义取契约的 {@code tools}。
     */
    CONTRACT,

    /**
     * 本体：工具语义由本体动作视图翻译，需同时声明 {@link SpecSourceField#ONTOLOGY}。
     */
    ONTOLOGY;

    /**
     * 按名称解析来源类型。
     *
     * @param name 类型名称
     * @return 来源类型；名称不匹配时为 {@code null}
     */
    public static @Nullable SpecSourceType of(String name) {
        for (SpecSourceType type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }

        return null;
    }
}
