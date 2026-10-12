package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.domain.hydrate.ExtractSpec;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.state.task.EntitySeed;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按 extracts 计条数、判空并抽实体候选。
 *
 * <p>无 extracts 时只认结构空，不猜字段名。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public final class EvidenceSemantics {

    /**
     * 从报文抽路径值。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 静态语义工具，禁止实例化。
     */
    private EvidenceSemantics() {

    }

    /**
     * 按各 extract 的 key 统计报文条数。
     *
     * @param contract 工具契约；可空
     * @param payload  工具报文体；可空
     * @return key 到条数的有序映射；无 extracts 或空报文为空 Map
     */
    public static Map<String, Integer> extractCounts(@Nullable ToolSpec contract, @Nullable JsonNode payload) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (Objects.isNull(contract) || Collections.isEmpty(contract.getExtracts()) || Objects.isNull(payload)) {
            return counts;
        }

        for (ExtractSpec extract : contract.getExtracts()) {
            String key = Strings.defaultIfBlank(extract.getKey(), extract.getPath());
            if (Strings.isBlank(key) || Strings.isBlank(extract.getPath())) {
                continue;
            }

            JsonNode at = JSON.lookupNode(payload, extract.getPath());
            counts.put(key, countAt(at, extract.getFields()));
        }

        return counts;
    }

    /**
     * 判定本次结果是否空。
     *
     * <p>有 extracts：全部 key 条数为 0 才空；无 extracts：按结构空。调用层失败一律非空。</p>
     *
     * @param contract 工具契约；可空
     * @param payload  工具报文体；可空
     * @param callOk   调用层是否成功
     * @return 视为空结果为 {@code true}
     */
    public static boolean empty(@Nullable ToolSpec contract, @Nullable JsonNode payload, boolean callOk) {
        if (!callOk) {
            return false;
        }

        if (Objects.nonNull(contract) && Collections.isNotEmpty(contract.getExtracts())) {
            Map<String, Integer> counts = extractCounts(contract, payload);
            if (counts.isEmpty()) {
                return true;
            }

            return counts.values().stream().allMatch(n -> n == 0);
        }

        return structurallyEmpty(payload);
    }

    /**
     * 统计路径节点条数。
     *
     * @param at     路径值；可空
     * @param fields 对象时要存在的字段
     * @return 条数；空节点为 0
     */
    private static int countAt(@Nullable JsonNode at, List<String> fields) {
        if (Objects.isNull(at) || at.isNull() || at.isMissingNode()) {
            return 0;
        }

        if (at.isArray()) {
            return at.size();
        }

        if (at.isObject()) {
            if (Collections.isEmpty(fields)) {
                return at.isEmpty() ? 0 : 1;
            }

            boolean any = fields.stream().anyMatch(field -> JSON.hasValue(at.get(field)));
            return any ? 1 : 0;
        }

        if (at.isString() && Strings.isBlank(at.asString())) {
            return 0;
        }

        return 1;
    }

    /**
     * 无 extracts 时按结构判断是否空报文。
     *
     * @param payload 报文体；可空
     * @return 空对象/数组/空白字符串为 {@code true}
     */
    private static boolean structurallyEmpty(@Nullable JsonNode payload) {
        if (Objects.isNull(payload) || payload.isNull() || payload.isMissingNode()) {
            return true;
        }

        if (payload.isArray() || payload.isObject()) {
            return payload.isEmpty();
        }

        return payload.isString() && Strings.isBlank(payload.asString());
    }

    /**
     * SUCCESS 且 extracts 声明了 entity 时，序列化候选列表写入证据实体字段。
     *
     * <p>写工具以入参为底、返回为准合并后再抽；返回标量时按 entity.scalar 命名成字段。</p>
     *
     * @param contract 工具契约；可空
     * @param payload  工具报文体；可空
     * @param args     本次调用入参；可空
     * @param success  是否调用成功
     * @return 实体候选 JSON；无可抽实体为 {@code null}
     */
    public static @Nullable String entitiesJson(@Nullable ToolSpec contract, @Nullable JsonNode payload, @Nullable JsonNode args, boolean success) {
        if (!success || Objects.isNull(contract) || Collections.isEmpty(contract.getExtracts()) || Objects.isNull(payload)) {
            return null;
        }

        ObjectNode base = contract.sideEffectOrDefault() == SideEffect.WRITE && args instanceof ObjectNode obj
            ? obj : JsonNodeFactory.instance.objectNode();
        List<EntitySeed> seeds = new ArrayList<>();
        for (ExtractSpec extract : contract.getExtracts()) {
            if (Objects.isNull(extract.getEntity()) || Strings.isBlank(extract.getEntity().getType())
                || Strings.isBlank(extract.getPath())) {
                continue;
            }

            JsonNode at = JSON.lookupNode(payload, extract.getPath());
            if (Objects.isNull(at) || at.isNull() || at.isMissingNode()) {
                continue;
            }

            if (at.isArray()) {
                at.forEach(item -> addSeed(seeds, extract.getEntity().seed(base, item)));
            } else {
                addSeed(seeds, extract.getEntity().seed(base, at));
            }
        }

        if (seeds.isEmpty()) {
            return null;
        }

        return JSON.obj2str(seeds);
    }

    /**
     * 收集非空种子。
     *
     * @param seeds 输出列表
     * @param seed  种子；可空
     */
    private static void addSeed(List<EntitySeed> seeds, @Nullable EntitySeed seed) {
        if (Objects.nonNull(seed)) {
            seeds.add(seed);
        }
    }
}
