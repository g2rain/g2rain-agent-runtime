package com.g2rain.agent.runtime.support;


import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 按 JSON Schema 收窄工具入参的顶层键，并列出仍缺的 required。
 *
 * <p>只处理顶层 {@code properties} / {@code required}，不做深层校验。
 * 会就地删除未声明键和非 required 的 null。</p>
 *
 * <pre>{@code
 * List<String> missing = JsonSchemaSupport.narrowTopLevel(args, schema);
 * Set<String> names = JsonSchemaSupport.propertyNames(schema);
 * }</pre>
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class JsonSchemaSupport {

    /**
     * JSON Schema 顶层字段处理工具；只负责 properties 和 required，不承担深层 schema 校验。
     */
    private JsonSchemaSupport() {

    }

    /**
     * 按 schema 删除未声明键与非 required 的 null，返回仍缺失的 required 字段名。
     *
     * <p>schema 为空时不改 {@code args}，返回空列表。空白字符串也视为缺失。</p>
     *
     * <pre>{@code
     * List<String> missing = JsonSchemaSupport.narrowTopLevel(args, schema);
     * }</pre>
     *
     * @param args   可变入参对象，就地修改
     * @param schema 工具入参 schema；为空则不收窄
     * @return 仍缺失、为 null 或空白字符串的 required 字段名
     */
    public static List<String> narrowTopLevel(ObjectNode args, @Nullable JsonNode schema) {
        // 无 schema 时保持入参原样
        if (Objects.isNull(schema)) {
            return List.of();
        }

        Set<String> properties = propertyNames(schema);
        Set<String> required = requiredNames(schema);

        // 删除 schema.properties 未声明的顶层键
        if (schema.has("properties")) {
            args.propertyNames().stream()
                .filter(name -> !properties.contains(name))
                .toList()
                .forEach(args::remove);
        }

        // 删除非 required 且值为 null 的顶层键
        args.propertyNames().stream()
            .filter(name -> args.get(name).isNull() && !required.contains(name))
            .toList()
            .forEach(args::remove);

        // 返回仍缺失、为 null 或空白字符串的 required 字段
        return required.stream().filter(field -> {
            JsonNode value = args.get(field);
            if (Objects.isNull(value) || value.isNull()) {
                return true;
            }
            return value.isString() && Strings.isBlank(value.asString());
        }).toList();
    }

    /**
     * 读取 schema 顶层 {@code properties} 字段名，保持声明顺序。
     *
     * @param schema 工具入参 schema；为空或无 properties 时返回空集
     * @return 顶层字段名
     */
    public static Set<String> propertyNames(@Nullable JsonNode schema) {
        // schema → properties 对象 → 顶层字段名
        return Optional.ofNullable(schema)
            .map(s -> s.get("properties"))
            .filter(JsonNode::isObject)
            // 保持 schema 声明顺序
            .map(properties -> new LinkedHashSet<>(properties.propertyNames()))
            .orElseGet(LinkedHashSet::new);
    }

    /**
     * 读取 schema 顶层 {@code required} 字段名，保持数组顺序。
     *
     * @param schema 工具入参 schema；为空或无 required 时返回空集
     * @return required 字段名
     */
    public static Set<String> requiredNames(@Nullable JsonNode schema) {
        // schema → required 数组 → 字符串项
        return Optional.ofNullable(schema)
            .map(s -> s.get("required"))
            .filter(JsonNode::isArray)
            .map(required -> StreamSupport.stream(required.spliterator(), false)
                .filter(JsonNode::isString)
                .map(JsonNode::asString)
                // 保持 required 数组顺序
                .collect(Collectors.toCollection(LinkedHashSet::new))
            ).orElseGet(LinkedHashSet::new);
    }
}
