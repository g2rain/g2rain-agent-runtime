package com.g2rain.agent.runtime.spi;

/**
 * 契约 {@code source} 的字段。
 *
 * @author alpha
 * @since 2026/11/21
 */
public enum SpecSourceField {

    /**
     * 来源类型，取值见 {@link SpecSourceType}；必填。
     */
    TYPE("type"),

    /**
     * 本体编码；{@link SpecSourceType#ONTOLOGY} 时必填。
     */
    ONTOLOGY("ontology"),

    /**
     * 操作 → 用法；{@link SpecSourceType#ONTOLOGY} 时可选。
     */
    USAGES("usages");

    /**
     * 字段名。
     */
    private final String key;

    /**
     * @param key 字段名
     */
    SpecSourceField(String key) {
        this.key = key;
    }

    /**
     * 字段名。
     *
     * @return 契约 JSON 中的字段名
     */
    public String key() {
        return key;
    }
}
