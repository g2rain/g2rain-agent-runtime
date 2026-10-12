package com.g2rain.agent.runtime.domain.state;


import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.Optional;

/**
 * 本轮平台身份快照（不可变）；不落 Redis / MySQL。
 *
 * <p>begin 时从请求身份拍定 organId、userId 等字段，供参数绑定 {@link com.g2rain.agent.runtime.domain.state.SourceType#PLATFORM}
 * 精确比对。调用方只读；{@link #toJson()} 返回深拷贝，避免外部改动污染本轮快照。</p>
 *
 * @param values 属性名 → JSON 值
 * @author alpha
 * @since 2026/10/4
 */
public record Platform(ObjectNode values) {

    /**
     * JSON 编解码。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    public Platform {
        // 缺值或非对象时收成空对象，保证后续 get/matches 只走 ObjectNode
        values = Objects.requireNonNullElseGet(values, JsonNodeFactory.instance::objectNode).deepCopy();
        values = values.isObject() ? values : JsonNodeFactory.instance.objectNode();
    }

    /**
     * 空快照。
     *
     * @return 不含任何平台字段的快照
     */
    public static Platform empty() {
        return new Platform(JsonNodeFactory.instance.objectNode());
    }

    /**
     * 读取一个平台字段。
     *
     * @param key 属性名；空白视为缺失
     * @return 字段取值；无键、空白或 JSON 空值时 empty
     */
    public Optional<JsonNode> get(@Nullable String key) {
        // 空白键或 JSON 空值不算平台字段，避免把缺失当成可绑定值
        if (Strings.isBlank(key) || !JSON.hasValue(values.get(key))) {
            return Optional.empty();
        }
        return Optional.of(values.get(key));
    }

    /**
     * 判断候选值是否与本轮平台字段精确相等，供 PLATFORM 来源校验。
     *
     * @param key   属性名
     * @param value 候选 JSON；空值不匹配
     * @return 字段存在且节点相等时为 {@code true}
     */
    public boolean matches(@Nullable String key, @Nullable JsonNode value) {
        Optional<JsonNode> expected = get(key);
        return expected.isPresent() && JSON.hasValue(value) && JSON.nodesEqual(expected.get(), value);
    }

    /**
     * 深拷贝当前快照，供提示拼装或任务记忆投影；调用方修改副本不影响本对象。
     *
     * @return 对象节点副本
     */
    public ObjectNode toJson() {
        return values.deepCopy();
    }
}
