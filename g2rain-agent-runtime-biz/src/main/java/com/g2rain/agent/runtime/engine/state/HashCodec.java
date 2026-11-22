package com.g2rain.agent.runtime.engine.state;


import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Redis Hash 与领域对象互转的通用能力。
 *
 * <p>Hash field 对应对象属性（或约定 field）。每个值都按 JSON 编码写入、按 JSON 解析读回，读写对称；
 * 字符串因此带引号存储，以 {@code {} 或 {@code [} 开头的自由文本不会被误当成结构。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public final class HashCodec {

    /**
     * 组内 Hash 的存在占位字段（字段名为空串，值为空串）。
     */
    public static final String MARK = "";

    /**
     * 属性与嵌套 JSON 编解码。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 静态编解码工具，禁止实例化。
     */
    private HashCodec() {
    }

    /**
     * 对象 → Hash。{@code skip} 中的属性不写入（身份在 key 上，或由 Lua 维护）。
     *
     * @param obj  领域对象
     * @param skip 不写入的属性名
     * @return field → JSON 文本
     */
    public static Map<String, String> toHash(Object obj, String... skip) {
        Set<String> ignored = Set.of(skip);
        Map<String, String> hash = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> property : JSON.obj2node(obj).properties()) {
            // null 属性不写入：与上次确认内容对比后由 flush 发 HDEL
            if (ignored.contains(property.getKey()) || property.getValue().isNull()) {
                continue;
            }

            hash.put(property.getKey(), JSON.obj2str(property.getValue()));
        }

        return hash;
    }

    /**
     * Hash → 对象。每个值已是 JSON 文本，拼成一个 JSON 对象后一次绑定。
     *
     * @param hash Redis hGetAll
     * @param type 目标类型
     * @param <T>  类型
     * @return 对象；hash 空、或只有 {@link #MARK} 占位时为 {@code null}
     */
    public static <T> @Nullable T toObject(Map<String, String> hash, Class<T> type) {
        if (Collections.isEmpty(hash)) {
            return null;
        }

        boolean any = false;
        StringJoiner json = new StringJoiner(",", "{", "}");
        for (Map.Entry<String, String> entry : hash.entrySet()) {
            // 占位字段不参与反序列化；没有任何真实字段时整对象视为空
            if (Objects.equals(entry.getKey(), MARK) || Strings.isBlank(entry.getValue())) {
                continue;
            }
            any = true;
            json.add(JSON.obj2str(JsonNodeFactory.instance.stringNode(entry.getKey())) + ":" + entry.getValue());
        }
        if (!any) {
            return null;
        }
        return JSON.str2obj(json.toString(), type);
    }
}
