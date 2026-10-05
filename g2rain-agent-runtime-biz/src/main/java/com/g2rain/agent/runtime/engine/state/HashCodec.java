package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.domain.graph.TaskEdge;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Redis Hash 与领域对象互转。
 *
 * <p>Hash field 对应对象属性（或约定 field）。嵌套对象与集合写成 JSON 字符串，标量写成字面量。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public final class HashCodec {

    /**
     * 任务图边列表在 Hash 中使用的 field 名称。
     */
    public static final String GRAPH_EDGES = "_edges";

    /**
     * 属性与嵌套 JSON 编解码。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 边列表反序列化类型。
     */
    private static final TypeReference<List<TaskEdge>> EDGES = new TypeReference<>() {

    };

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
     * @return field → 字符串值
     */
    @SuppressWarnings("ConstantConditions")
    public static Map<String, String> toHash(Object obj, String... skip) {
        Set<String> ignored = Set.of(skip);
        Map<String, String> hash = new LinkedHashMap<>();
        Map<String, Object> properties = JSON.str2map(JSON.obj2str(obj));
        if (Collections.isEmpty(properties)) {
            return hash;
        }

        properties.forEach((field, value) -> {
            // null 属性不写入：由 deletedFields 生成 HDEL，HSET 不能携带空值
            if (ignored.contains(field) || Objects.isNull(value)) {
                return;
            }

            hash.put(field, scalar(value) ? String.valueOf(value) : JSON.obj2str(value));
        });

        return hash;
    }

    /**
     * 值为 null 的属性名，flush 时应对这些 field 发 HDEL。
     *
     * @param obj  领域对象
     * @param skip 与 {@link #toHash} 相同的跳过属性
     * @return 待删除的 field 名
     */
    public static List<String> deletedFields(Object obj, String... skip) {
        Set<String> ignored = Set.of(skip);
        List<String> fields = new ArrayList<>();
        Map<String, Object> properties = JSON.str2map(JSON.obj2str(obj));
        if (Collections.isEmpty(properties)) {
            return fields;
        }

        properties.forEach((field, value) -> {
            //noinspection ConstantValue
            if (!ignored.contains(field) && Objects.isNull(value)) {
                fields.add(field);
            }
        });

        return fields;
    }

    /**
     * Hash → 对象。每个 field 若是 JSON 对象/数组则先解析再绑定。
     *
     * @param hash Redis hGetAll
     * @param type 目标类型
     * @param <T>  类型
     * @return 对象；hash 空为 {@code null}
     */
    public static <T> @Nullable T toObject(Map<String, String> hash, Class<T> type) {
        if (Collections.isEmpty(hash)) {
            return null;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        hash.forEach((field, raw) -> {
            Object value = decode(raw);
            if (Objects.nonNull(value)) {
                fields.put(field, value);
            }
        });
        return JSON.str2obj(JSON.obj2str(fields), type);
    }

    /**
     * 任务图 → Hash：field = nodeId，另有 {@link #GRAPH_EDGES}。
     *
     * @param graph 任务图
     * @return Hash
     */
    public static Map<String, String> graphHash(TaskGraph graph) {
        Map<String, String> hash = new LinkedHashMap<>();
        graph.getNodes().forEach(node -> hash.put(node.getNodeId(), JSON.obj2str(node)));
        hash.put(GRAPH_EDGES, JSON.obj2str(graph.getEdges()));
        return hash;
    }

    /**
     * Hash → 任务图。
     *
     * @param hash Redis hGetAll
     * @return 图；hash 空为 {@code null}
     */
    public static @Nullable TaskGraph graphOf(Map<String, String> hash) {
        if (Collections.isEmpty(hash)) {
            return null;
        }
        TaskGraph graph = new TaskGraph();
        List<TaskNode> nodes = new ArrayList<>();
        hash.forEach((field, raw) -> {
            if (Objects.equals(field, GRAPH_EDGES) || Strings.isBlank(raw)) {
                return;
            }
            TaskNode node = JSON.str2obj(raw, TaskNode.class);
            if (Objects.nonNull(node)) {
                nodes.add(node);
            }
        });
        graph.setNodes(nodes);
        String edges = hash.get(GRAPH_EDGES);
        if (Strings.isBlank(edges)) {
            return graph;
        }
        List<TaskEdge> parsed = JSON.str2obj(edges, EDGES);
        graph.setEdges(parsed);
        return graph;
    }

    /**
     * 解析单个 Hash 值：JSON 对象/数组进树，其余当字符串。
     *
     * @param raw Redis 字符串
     * @return 解码后的值；空白 field 为 {@code null}
     */
    private static @Nullable Object decode(String raw) {
        if (Strings.isBlank(raw)) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("{") || text.startsWith("[")) {
            return JSON.str2node(text);
        }
        return raw;
    }

    /**
     * 标量直接写字面量，避免再包一层 JSON 引号。
     *
     * @param value 属性值
     * @return 是否为字符串、数字、布尔或枚举
     */
    private static boolean scalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean
            || value instanceof Enum<?>;
    }
}
