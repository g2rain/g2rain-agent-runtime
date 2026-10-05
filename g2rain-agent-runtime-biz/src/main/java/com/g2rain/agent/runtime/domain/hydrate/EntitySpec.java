package com.g2rain.agent.runtime.domain.hydrate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * 实体沉淀声明：抽取或输出如何变成会话记忆实体。
 *
 * <p>type + id 构成索引键 {@code type:id}；persist 时并入 {@link com.g2rain.agent.runtime.domain.state.task.EntityEntry}。</p>
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
     * 标识字段；缺省取值本身。
     */
    private @Nullable String id;

    /**
     * 显示名字段。
     */
    private @Nullable String name;
}
