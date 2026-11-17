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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Redis Hash 与领域对象互转。
 *
 * <p>Hash field 对应对象属性（或约定 field）。每个值都按 JSON 编码写入、按 JSON 解析读回，读写对称；
 * 字符串因此带引号存储，以 {@code {} 或 {@code [} 开头的自由文本不会被误当成结构。</p>
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
     * 任务图节点顺序在 Hash 中使用的 field 名称；Hash 本身无序，恢复时按此还原规划顺序。
     */
    public static final String GRAPH_ORDER = "_order";

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
     * 节点顺序反序列化类型。
     */
    private static final TypeReference<List<String>> ORDER = new TypeReference<>() {

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
     * @return field → JSON 文本
     */
    public static Map<String, String> toHash(Object obj, String... skip) {
        Set<String> ignored = Set.of(skip);
        Map<String, String> hash = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> property : JSON.obj2node(obj).properties()) {
            // null 属性不写入：由 deletedFields 生成 HDEL，HSET 不能携带空值
            if (ignored.contains(property.getKey()) || property.getValue().isNull()) {
                continue;
            }

            hash.put(property.getKey(), JSON.obj2str(property.getValue()));
        }

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
        for (Map.Entry<String, JsonNode> property : JSON.obj2node(obj).properties()) {
            if (!ignored.contains(property.getKey()) && property.getValue().isNull()) {
                fields.add(property.getKey());
            }
        }

        return fields;
    }

    /**
     * Hash → 对象。每个值已是 JSON 文本，拼成一个 JSON 对象后一次绑定。
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

        StringJoiner json = new StringJoiner(",", "{", "}");
        hash.forEach((field, raw) -> {
            if (Strings.isNotBlank(raw)) {
                json.add(JSON.obj2str(JsonNodeFactory.instance.stringNode(field)) + ":" + raw);
            }
        });
        return JSON.str2obj(json.toString(), type);
    }

    /**
     * 任务图 → Hash：field = nodeId，另有 {@link #GRAPH_EDGES} 与 {@link #GRAPH_ORDER}。
     *
     * @param graph 任务图
     * @return Hash
     */
    public static Map<String, String> graphHash(TaskGraph graph) {
        Map<String, String> hash = new LinkedHashMap<>();
        graph.getNodes().forEach(node -> hash.put(node.getNodeId(), JSON.obj2str(node)));
        hash.put(GRAPH_EDGES, JSON.obj2str(graph.getEdges()));
        hash.put(GRAPH_ORDER, JSON.obj2str(graph.getNodes().stream().map(TaskNode::getNodeId).toList()));
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
        Map<String, TaskNode> byId = new LinkedHashMap<>();
        hash.forEach((field, raw) -> {
            if (Objects.equals(field, GRAPH_EDGES) || Objects.equals(field, GRAPH_ORDER) || Strings.isBlank(raw)) {
                return;
            }
            TaskNode node = JSON.str2obj(raw, TaskNode.class);
            if (Objects.nonNull(node)) {
                byId.put(node.getNodeId(), node);
            }
        });
        // 按记下的规划顺序还原，顺序表之外的节点追加在后
        List<TaskNode> nodes = new ArrayList<>();
        String order = hash.get(GRAPH_ORDER);
        if (Strings.isNotBlank(order)) {
            List<String> nodeIds = Objects.requireNonNullElse(JSON.str2obj(order, ORDER), List.of());
            nodeIds.stream().map(byId::remove).filter(Objects::nonNull).forEach(nodes::add);
        }
        nodes.addAll(byId.values());
        graph.setNodes(nodes);
        String edges = hash.get(GRAPH_EDGES);
        if (Strings.isBlank(edges)) {
            return graph;
        }
        List<TaskEdge> parsed = JSON.str2obj(edges, EDGES);
        graph.setEdges(parsed);
        return graph;
    }
}
