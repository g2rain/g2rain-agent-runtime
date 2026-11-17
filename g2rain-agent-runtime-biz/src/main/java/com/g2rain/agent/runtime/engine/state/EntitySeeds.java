package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.domain.state.task.EntitySeed;
import com.g2rain.agent.runtime.spec.EntitySpec;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 按实体声明从 JSON 抽出 {@link EntitySeed}。
 *
 * <p>用法：{@code EntitySeeds.of(spec, base, item)}；写工具以入参为底、返回为准合并。</p>
 *
 * @author alpha
 * @since 2026/11/17
 */
public final class EntitySeeds {

    /**
     * JSON 编解码。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 未配置 {@link EntitySpec#getId()} 时使用的标识字段名。
     */
    private static final String DEFAULT_ID_FIELD = "id";

    /**
     * 未配置 {@link EntitySpec#getName()} 时使用的显示名字段名。
     */
    private static final String DEFAULT_NAME_FIELD = "name";

    /**
     * 静态工具，禁止实例化。
     */
    private EntitySeeds() {
    }

    /**
     * 以 base 为底、item 为准合并后抽出实体种子。
     *
     * @param spec 实体声明
     * @param base 底；写工具为入参，其余为空对象
     * @param item 值：对象直接合并，标量按 {@link EntitySpec#getScalar()} 命名后合并
     * @return 种子；值不可用或取不到 id 为 {@code null}
     */
    public static @Nullable EntitySeed of(EntitySpec spec, ObjectNode base, @Nullable JsonNode item) {
        if (Strings.isBlank(spec.getType()) || !JSON.hasValue(item)) {
            return null;
        }

        ObjectNode merged = base.deepCopy();
        String scalar = spec.getScalar();
        if (item instanceof ObjectNode obj) {
            merged.setAll(obj);
        } else if (item.isValueNode() && Strings.isNotBlank(scalar)) {
            merged.set(scalar, item);
        } else {
            return null;
        }

        JsonNode idNode = merged.get(Strings.defaultIfBlank(spec.getId(), DEFAULT_ID_FIELD));
        if (!JSON.hasValue(idNode) || Strings.isBlank(idNode.asString())) {
            return null;
        }

        String key = idNode.asString();
        JsonNode nameNode = merged.get(Strings.defaultIfBlank(spec.getName(), DEFAULT_NAME_FIELD));
        String display = JSON.hasValue(nameNode) ? nameNode.asString() : key;
        ObjectNode attrs = JsonNodeFactory.instance.objectNode();
        merged.properties().forEach(field -> {
            if (field.getValue().isValueNode() && JSON.hasValue(field.getValue())) {
                attrs.set(field.getKey(), field.getValue());
            }
        });

        return new EntitySeed(spec.getType(), key, display, attrs);
    }
}
