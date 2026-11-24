package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.domain.graph.TaskEdge;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.task.EntityEntry;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.spec.SideEffect;
import com.g2rain.agent.runtime.spi.Attachment;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redis 结构与领域对象成对编解码。
 *
 * <p>每种结构的空值写法、{@link HashCodec#MARK} 处理和跳过字段都集中在此，供回灌与 flush 共用。</p>
 *
 * @author alpha
 * @since 2026/11/22
 */
public final class StateCodec {

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
     * 实体 Hash 值的反序列化类型。
     */
    private static final TypeReference<EntityEntry> ENTITY = new TypeReference<>() {

    };

    /**
     * 共享值的来源包装类型。
     */
    private static final TypeReference<Sourced<Object>> SOURCED = new TypeReference<>() {

    };

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
     * 回复附件列表反序列化类型。
     */
    private static final TypeReference<List<Attachment>> ATTACHMENTS = new TypeReference<>() {

    };

    /**
     * 静态编解码工具，禁止实例化。
     */
    private StateCodec() {
    }

    /**
     * 附件列表 → 库列 JSON；空列表返回 {@code null}（不写空数组）。
     *
     * @param attachments 附件；可空
     * @return JSON 文本；无可写内容时为 {@code null}
     */
    public static @Nullable String attachmentsJson(@Nullable List<Attachment> attachments) {
        if (Objects.isNull(attachments) || attachments.isEmpty()) {
            return null;
        }
        return JSON.obj2str(attachments);
    }

    /**
     * 库列 JSON → 附件列表；空白或空数组返回 {@code null}。
     *
     * @param raw JSON 文本；可空
     * @return 附件列表；无可读内容时为 {@code null}
     */
    public static @Nullable List<Attachment> attachments(@Nullable String raw) {
        if (Strings.isBlank(raw)) {
            return null;
        }

        List<Attachment> list = JSON.str2obj(raw, ATTACHMENTS);
        if (list.isEmpty()) {
            return null;
        }

        return List.copyOf(list);
    }

    /**
     * Redis {@code state} Hash → 会话栅栏；{@code conversationId} 在 key 上。
     *
     * @param conversationId 会话 id
     * @param hash           hGetAll 结果
     * @return 栅栏；Hash 空或缺字段时补默认值
     */
    public static ConversationFence fence(String conversationId, Map<String, String> hash) {
        ConversationFence parsed = HashCodec.toObject(hash, ConversationFence.class);
        if (Objects.isNull(parsed)) {
            return new ConversationFence(conversationId, null, null, 0L, null, System.currentTimeMillis());
        }
        long updatedAt = parsed.updatedAt() == 0L ? System.currentTimeMillis() : parsed.updatedAt();
        return new ConversationFence(conversationId, parsed.userId(), parsed.activeTaskId(), parsed.version(),
            parsed.lastTraceId(), updatedAt);
    }

    /**
     * 会话栅栏 → Hash：不含 {@code version}、不含 null 字段；{@code conversationId} 不进 Hash。
     *
     * @param fence 栅栏
     * @return field → JSON 文本
     */
    public static Map<String, String> fenceHash(ConversationFence fence) {
        return HashCodec.toHash(fence, "version");
    }

    /**
     * 栅栏中值为 null、flush 时应 HDEL 的字段名（不含 {@code version}）。
     *
     * @param fence 栅栏
     * @return 待删除的字段名
     */
    public static List<String> fenceCleared(ConversationFence fence) {
        List<String> fields = new ArrayList<>();
        if (Objects.isNull(fence.userId())) {
            fields.add("userId");
        }
        if (Objects.isNull(fence.activeTaskId())) {
            fields.add("activeTaskId");
        }
        if (Objects.isNull(fence.lastTraceId())) {
            fields.add("lastTraceId");
        }
        return fields;
    }

    /**
     * 实体 Hash → {@code type:id →} 实体。
     *
     * @param hash Redis hGetAll
     * @return 实体索引；空 Map 表示没有实体
     */
    public static Map<String, EntityEntry> entities(Map<String, String> hash) {
        Map<String, EntityEntry> entities = new LinkedHashMap<>();
        hash.forEach((field, raw) -> entities.put(field, JSON.str2obj(raw, ENTITY)));
        return entities;
    }

    /**
     * 实体索引 → Hash。
     *
     * @param entities {@code type:id →} 实体
     * @return field → JSON 文本
     */
    public static Map<String, String> entitiesHash(Map<String, EntityEntry> entities) {
        Map<String, String> hash = new LinkedHashMap<>();
        entities.forEach((field, entry) -> hash.put(field, JSON.obj2str(entry)));
        return hash;
    }

    /**
     * 共享值 Hash → 带源值；跳过 {@link HashCodec#MARK}。
     *
     * @param hash Redis hGetAll
     * @return 带源值；空 Map 表示没有共享字段
     */
    public static Map<String, Sourced<?>> shared(Map<String, String> hash) {
        Map<String, Sourced<?>> shared = new LinkedHashMap<>();
        hash.forEach((field, raw) -> {
            if (Objects.equals(field, HashCodec.MARK)) {
                return;
            }
            shared.put(field, JSON.str2obj(raw, SOURCED));
        });
        return shared;
    }

    /**
     * 共享值 → Hash；{@code marked} 为 true 时带 {@link HashCodec#MARK}（任务共享要，会话共享不要）。
     *
     * @param shared 带源值
     * @param marked 是否写入占位字段
     * @return field → JSON 文本
     */
    public static Map<String, String> sharedHash(Map<String, Sourced<?>> shared, boolean marked) {
        Map<String, String> hash = new LinkedHashMap<>();
        if (marked) {
            hash.put(HashCodec.MARK, "");
        }
        shared.forEach((field, value) -> hash.put(field, JSON.obj2str(value)));
        return hash;
    }

    /**
     * 任务元数据 Hash → 对象。
     *
     * @param hash Redis hGetAll
     * @return 元数据；hash 空或只有占位为 {@code null}
     */
    public static @Nullable TaskMetadata metadata(Map<String, String> hash) {
        return HashCodec.toObject(hash, TaskMetadata.class);
    }

    /**
     * 任务元数据 → Hash：不含 {@code conversationId} / {@code taskId} / {@code version}，不含 null 字段。
     *
     * @param metadata 元数据
     * @return field → JSON 文本
     */
    public static Map<String, String> metadataHash(TaskMetadata metadata) {
        return HashCodec.toHash(metadata, "conversationId", "taskId", "version");
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
    public static @Nullable TaskGraph graph(Map<String, String> hash) {
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

    /**
     * 断点原始文本 → 对象；空白即没有。
     *
     * @param raw Redis GET 结果；可空
     * @return 断点；空白为 {@code null}
     */
    public static @Nullable TaskCheckpoint checkpoint(@Nullable String raw) {
        return Strings.isBlank(raw) ? null : JSON.str2obj(raw, TaskCheckpoint.class);
    }

    /**
     * 断点 → Redis String 值；没有则写空串。
     *
     * @param checkpoint 断点；可空
     * @return JSON 文本或 {@code ""}
     */
    public static String checkpointValue(@Nullable TaskCheckpoint checkpoint) {
        return Objects.isNull(checkpoint) ? "" : JSON.obj2str(checkpoint);
    }

    /**
     * 前情原始文本 → 对象；空白即没有。
     *
     * @param raw Redis GET 结果；可空
     * @return 前情；空白为 {@code null}
     */
    public static @Nullable Prior prior(@Nullable String raw) {
        return Strings.isBlank(raw) ? null : JSON.str2obj(raw, Prior.class);
    }

    /**
     * 前情 → Redis String 值；没有则写空串。
     *
     * @param prior 前情；可空
     * @return JSON 文本或 {@code ""}
     */
    public static String priorValue(@Nullable Prior prior) {
        return Objects.isNull(prior) ? "" : JSON.obj2str(prior);
    }

    /**
     * 节点证据 Hash → 列表；跳过 {@link HashCodec#MARK}，按轮次排序。
     *
     * @param hash Redis hGetAll
     * @return 证据列表
     */
    public static List<EvidenceHead> evidence(Map<String, String> hash) {
        List<EvidenceHead> evidence = new ArrayList<>();
        hash.forEach((id, raw) -> {
            if (Objects.equals(id, HashCodec.MARK)) {
                return;
            }
            EvidenceHead head = JSON.str2obj(raw, EvidenceHead.class);
            if (Objects.nonNull(head)) {
                evidence.add(head);
            }
        });
        evidence.sort(Comparator.comparingInt(EvidenceHead::round));
        return evidence;
    }

    /**
     * 节点证据列表 → Hash；带 {@link HashCodec#MARK}。
     *
     * @param evidence 证据列表
     * @return field → JSON 文本
     */
    public static Map<String, String> evidenceHash(List<EvidenceHead> evidence) {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put(HashCodec.MARK, "");
        evidence.forEach(head -> hash.put(head.evidenceId(), JSON.obj2str(head)));
        return hash;
    }

    /**
     * 写索引：只含有幂等键的写证据，带 {@link HashCodec#MARK}。
     *
     * @param evidence 本任务已打开节点上的证据
     * @return field → JSON 文本
     */
    public static Map<String, String> writesHash(List<EvidenceHead> evidence) {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put(HashCodec.MARK, "");
        for (EvidenceHead head : evidence) {
            if (!Objects.equals(head.sideEffect(), SideEffect.WRITE) || Strings.isBlank(head.idempotencyKey())) {
                continue;
            }
            WriteIndexEntry entry = WriteIndexEntry.of(head);
            if (Objects.isNull(entry)) {
                continue;
            }
            hash.put(WriteIndexEntry.field(head.toolName(), head.idempotencyKey()), JSON.obj2str(entry));
        }
        return hash;
    }

    /**
     * Redis 对话列表（新→旧）→ 时间正序消息。
     *
     * @param raw lRange 结果
     * @return 交互历史
     */
    public static List<DialogueMessage> dialogue(List<String> raw) {
        List<String> items = new ArrayList<>(raw);
        java.util.Collections.reverse(items);
        List<DialogueMessage> dialogue = new ArrayList<>();
        for (String item : items) {
            DialogueMessage message = JSON.str2obj(item, DialogueMessage.class);
            if (Objects.nonNull(message)) {
                dialogue.add(message);
            }
        }
        return dialogue;
    }

    /**
     * Redis 任务记录列表（新→旧）→ 时间正序记录。
     *
     * @param raw lRange 结果
     * @return 任务记录
     */
    public static List<TaskRecord> records(List<String> raw) {
        List<String> items = new ArrayList<>(raw);
        java.util.Collections.reverse(items);
        List<TaskRecord> records = new ArrayList<>();
        for (String item : items) {
            TaskRecord record = JSON.str2obj(item, TaskRecord.class);
            if (Objects.nonNull(record)) {
                records.add(record);
            }
        }
        return records;
    }

    /**
     * 对话消息 → Redis 列表元素。
     *
     * @param message 消息
     * @return JSON 文本
     */
    public static String message(DialogueMessage message) {
        return JSON.obj2str(message);
    }

    /**
     * 任务记录 → Redis 列表元素。
     *
     * @param record 记录
     * @return JSON 文本
     */
    public static String record(TaskRecord record) {
        return JSON.obj2str(record);
    }
}
