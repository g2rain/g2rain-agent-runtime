package com.g2rain.agent.runtime.spec;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * 实体沉淀声明：抽取或输出如何变成会话记忆实体。
 *
 * <p>type + id 构成索引键 {@code type:id}；persist 时并入会话记忆实体条目。</p>
 * <p>id、name 填字段名；值为标量时按 scalar 命名成字段后同样按字段名取。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Getter
@Setter
@NoArgsConstructor
public class EntitySpec {

    /**
     * 实体类型。
     */
    private String type;

    /**
     * 标识字段名；缺省 {@code id}。
     */
    private @Nullable String id;

    /**
     * 显示名字段名；缺省 {@code name}。
     */
    private @Nullable String name;

    /**
     * 标量的字段名：值为标量时包成该字段（如保存接口只回主键时为 {@code id}）；未配置则标量不成实体。
     */
    private @Nullable String scalar;
}
