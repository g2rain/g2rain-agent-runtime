package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.hydrate.EntitySpec;
import com.g2rain.agent.runtime.domain.hydrate.OutputSpec;
import com.g2rain.agent.runtime.domain.hydrate.ShareScope;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.EntityEntry;
import com.g2rain.agent.runtime.domain.state.task.EntitySeed;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.PlatformCatalog;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * 把证据候选与 outputs.entity 并入会话实体索引。
 *
 * <p>{@link #captureOutputs} 在节点 finish 通过后记下候选并按 share 写出；
 * {@link #collect} 在 persist 时合并进 {@code type:id}。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Component
@RequiredArgsConstructor
public class EntityExtractor {

    /**
     * JSON 编解码。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 平台字段目录：与 catalog.keys() 同名的键禁止写入 shared，也不进入实体属性。
     */
    private final PlatformCatalog platformCatalog;

    /**
     * 实体索引条数上限。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 实体候选列表的反序列化类型。
     */
    private static final TypeReference<List<EntitySeed>> SEEDS = new TypeReference<>() {

    };

    /**
     * 仅记产出的实体使用的空属性对象。
     */
    private static final JsonNode EMPTY_ATTRS = JsonNodeFactory.instance.objectNode();

    /**
     * finish 通过后：记下 outputs.entity 候选，并按 share 写出 TASK/SESSION。
     *
     * @param node     当前节点
     * @param contract 技能契约；可空则直接返回
     */
    public void captureOutputs(NodeScope node, @Nullable SkillContract contract) {
        if (Objects.isNull(contract) || Collections.isEmpty(contract.getOutputs())) {
            return;
        }
        List<EntitySeed> seeds = new java.util.ArrayList<>();
        contract.getOutputs().forEach((key, spec) -> {
            Sourced<?> sourced = node.outputs().get(key);
            if (Objects.isNull(sourced)) {
                return;
            }
            share(node, key, spec, sourced);
            EntitySeed seed = outputSeed(spec.getEntity(), sourced.value());
            if (Objects.nonNull(seed)) {
                seeds.add(seed);
            }
        });
        if (!seeds.isEmpty()) {
            node.note(NodeNoteKey.ENTITY_OUTPUTS, seeds);
        }
    }

    /**
     * persist 时把 SUCCESS 证据候选与 DONE 节点的 output 候选并入 {@code type:id}。
     *
     * @param turn 本轮视图；非 {@link DefaultTurnScope} 时忽略
     */
    public void collect(TurnScope turn) {
        String taskId = turn.task().map(TaskMetadata::getTaskId).orElse(null);
        if (Strings.isBlank(taskId)) {
            return;
        }

        if (!(turn instanceof DefaultTurnScope scope)) {
            return;
        }

        // 并入成功证据带出的实体，以及已完成节点产出声明的实体
        scope.nodes().values().forEach(node -> {
            node.evidence().stream()
                .filter(head -> head.status() == EvidenceStatus.SUCCESS)
                .forEach(head -> mergeSeeds(scope, parse(head.entities()), head.evidenceId(), taskId, false));
            if (done(scope, node.nodeId())) {
                Object raw = node.note(NodeNoteKey.ENTITY_OUTPUTS);
                mergeSeeds(scope, parse(JSON.obj2str(raw)), null, taskId, true);
            }
        });

        // 无归属任务的实体补记为本任务
        for (var entry : new LinkedHashMap<>(scope.entityIndex()).entrySet()) {
            EntityEntry current = entry.getValue();
            if (Objects.isNull(current.taskId())) {
                scope.entityIndex().put(entry.getKey(), new EntityEntry(
                    current.displayName(), current.attrs(), taskId, current.evidenceId(),
                    current.seenAt(), current.mentions()
                ));
            }
        }

        // 超过上限时从最早写入的开始淘汰
        int max = Math.max(0, runtimeProperties.getState().getEntitiesMax());
        while (scope.entityIndex().size() > max) {
            String first = scope.entityIndex().keySet().iterator().next();
            scope.entityIndex().remove(first);
        }
    }

    /**
     * 图节点是否已 DONE。
     *
     * @param turn   本轮视图
     * @param nodeId 节点 id
     * @return 节点存在且已完成
     */
    private static boolean done(DefaultTurnScope turn, String nodeId) {
        var node = turn.graph().find(nodeId);
        return Objects.nonNull(node) && node.getStatus() == NodeStatus.DONE;
    }

    /**
     * 按 output 契约把值写入任务共享或会话共享。
     *
     * @param node    当前节点
     * @param key     output 键
     * @param spec    output 契约
     * @param sourced 带源值
     */
    private void share(NodeScope node, String key, OutputSpec spec, Sourced<?> sourced) {
        if (!(node instanceof DefaultNodeScope scoped) || Objects.isNull(spec.getShare())) {
            return;
        }
        if (platformCatalog.keys().contains(key)) {
            throw new BusinessException(RuntimeErrorCode.PLATFORM_FIELD_FORBIDDEN, key);
        }
        if (spec.getShare() == ShareScope.SESSION) {
            scoped.turn().shareSession(key, sourced);
            return;
        }
        if (spec.getShare() == ShareScope.TASK) {
            scoped.turn().share(key, sourced);
        }
    }

    /**
     * 从正式产出抽出一条实体种子。
     *
     * @param entity 实体抽取声明；可空
     * @param value  产出值；可空
     * @return 种子；声明或值不足时为 {@code null}
     */
    private static @Nullable EntitySeed outputSeed(@Nullable EntitySpec entity, @Nullable Object value) {
        if (Objects.isNull(entity) || Objects.isNull(value)) {
            return null;
        }
        JsonNode node = value instanceof JsonNode json ? json : JSON.obj2node(value);
        return entity.seed(JsonNodeFactory.instance.objectNode(), node);
    }

    /**
     * 解析证据上的实体种子 JSON。
     *
     * @param json 种子数组文本；可空
     * @return 种子列表；空白或 {@code null} 文本为空列表
     */
    private static List<EntitySeed> parse(@Nullable String json) {
        if (Strings.isBlank(json) || "null".equals(json)) {
            return List.of();
        }

        return JSON.str2obj(json, SEEDS);
    }

    /**
     * 把种子合并进本轮实体索引；属性中的平台字段不入索引。
     *
     * @param turn       本轮视图
     * @param seeds      待合并种子
     * @param evidenceId 来源证据 id；可空
     * @param taskId     当前任务 id
     * @param outputOnly 为 {@code true} 时不覆盖已有属性
     */
    private void mergeSeeds(DefaultTurnScope turn, List<EntitySeed> seeds, @Nullable String evidenceId,
                                   String taskId, boolean outputOnly) {
        long now = System.currentTimeMillis();
        for (EntitySeed seed : seeds) {
            if (Strings.isBlank(seed.type()) || Strings.isBlank(seed.id())) {
                continue;
            }
            String key = seed.type() + ":" + seed.id();
            JsonNode attrs = withoutPlatform(seed.attrs());
            EntityEntry current = turn.entityIndex().get(key);
            if (Objects.isNull(current)) {
                turn.entityIndex().put(key, new EntityEntry(Strings.defaultIfBlank(seed.displayName(), seed.id()),
                    outputOnly ? EMPTY_ATTRS : attrs, taskId, evidenceId, now, 1));
                continue;
            }
            // 种子没有展示名（如保存只回主键）时保留已有名称，不用 id 覆盖
            String nextName = Strings.defaultIfBlank(seed.displayName(), current.displayName());
            JsonNode nextAttrs = current.attrs();
            String nextEvidence = current.evidenceId();
            if (!outputOnly && wins(current, evidenceId, now)) {
                nextAttrs = attrs;
                nextEvidence = evidenceId;
            }
            turn.entityIndex().put(key, new EntityEntry(nextName, nextAttrs, taskId, nextEvidence, now,
                current.mentions() + 1));
        }
    }

    /**
     * 去掉与平台字段目录同名的属性。
     *
     * @param attrs 种子属性
     * @return 不含平台字段的属性；非对象原样返回
     */
    private JsonNode withoutPlatform(JsonNode attrs) {
        if (!(attrs instanceof ObjectNode object)) {
            return attrs;
        }
        ObjectNode copy = object.deepCopy();
        copy.remove(platformCatalog.keys());
        return copy;
    }

    /**
     * 较新 SUCCESS 胜出；时间相同则 evidenceId 字典序更大者胜出。
     *
     * @param current    索引里已有实体
     * @param evidenceId 新证据 id；可空则不胜出
     * @param now        本次见到时间
     * @return 是否用新证据覆盖属性
     */
    private static boolean wins(EntityEntry current, @Nullable String evidenceId, long now) {
        if (Strings.isBlank(evidenceId)) {
            return false;
        }
        if (current.seenAt() < now) {
            return true;
        }
        if (current.seenAt() > now) {
            return false;
        }
        String previous = Objects.toString(current.evidenceId(), "");
        return evidenceId.compareTo(previous) > 0;
    }
}
