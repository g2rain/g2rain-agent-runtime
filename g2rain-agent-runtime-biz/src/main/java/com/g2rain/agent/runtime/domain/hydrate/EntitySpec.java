package com.g2rain.agent.runtime.domain.hydrate;

import com.g2rain.agent.runtime.domain.state.task.EntitySeed;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 实体沉淀声明：抽取或输出如何变成会话记忆实体。
 *
 * <p>type + id 构成索引键 {@code type:id}；persist 时并入 {@link com.g2rain.agent.runtime.domain.state.task.EntityEntry}。</p>
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
     * JSON 编解码。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

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

    /**
     * 以 base 为底、item 为准合并后抽出实体种子。
     *
     * @param base 底；写工具为入参，其余为空对象
     * @param item 值：对象直接合并，标量按 {@link #scalar} 命名后合并
     * @return 种子；值不可用或取不到 id 为 {@code null}
     */
    public @Nullable EntitySeed seed(ObjectNode base, @Nullable JsonNode item) {
        if (Strings.isBlank(type) || !JSON.hasValue(item)) {
            return null;
        }

        ObjectNode merged = base.deepCopy();
        if (item instanceof ObjectNode obj) {
            merged.setAll(obj);
        } else if (item.isValueNode() && Strings.isNotBlank(scalar)) {
            merged.set(scalar, item);
        } else {
            return null;
        }

        JsonNode idNode = merged.get(Strings.defaultIfBlank(id, "id"));
        if (!JSON.hasValue(idNode) || Strings.isBlank(idNode.asString())) {
            return null;
        }

        String key = idNode.asString();
        JsonNode nameNode = merged.get(Strings.defaultIfBlank(name, "name"));
        String display = JSON.hasValue(nameNode) ? nameNode.asString() : key;
        ObjectNode attrs = JsonNodeFactory.instance.objectNode();
        merged.properties().forEach(field -> {
            if (field.getValue().isValueNode() && JSON.hasValue(field.getValue())) {
                attrs.set(field.getKey(), field.getValue());
            }
        });

        return new EntitySeed(type, key, display, attrs);
    }
}
