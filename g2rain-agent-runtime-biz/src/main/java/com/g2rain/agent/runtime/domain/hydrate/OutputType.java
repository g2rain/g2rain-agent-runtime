package com.g2rain.agent.runtime.domain.hydrate;

/**
 * 节点输出形态。
 *
 * <p>参与完成判定与下游绑定：单值按存在性检查，行式集合还要看 {@code minItems} 与字段投影。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public enum OutputType {

    /**
     * 单值。
     * <p>一个标量或对象，完成判定看是否存在且非空。</p>
     */
    VALUE,

    /**
     * 行式集合。
     * <p>多行记录；契约用 {@code fields} 声明每行保留哪些列。</p>
     */
    LIST
}
