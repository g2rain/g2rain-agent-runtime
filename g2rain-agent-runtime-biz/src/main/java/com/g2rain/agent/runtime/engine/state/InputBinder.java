package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.domain.hydrate.InputSpec;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.SourceType;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.EntityEntry;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按技能契约的 inputs 为当前节点绑定带源值。
 *
 * <p>省略 {@code from} 时取最近上游同名 output。只补尚未存在的键，不覆盖已绑定来源。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
final class InputBinder {

    /**
     * 任务前情实体属性读取。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 前情实体的 id 属性名（id 取自索引键 {@code type:id}）。
     */
    private static final String ID = "id";

    /**
     * 前情实体的展示名属性名。
     */
    private static final String DISPLAY_NAME = "displayName";

    /**
     * 静态绑定工具，禁止实例化。
     */
    private InputBinder() {

    }

    /**
     * 解析契约 inputs，返回本节点应绑定的键值。
     *
     * @param turn            本轮视图
     * @param nodeId          当前节点
     * @param contract        技能契约；可空则返回空 Map
     * @param upstreamOutputs 已完成上游节点的 outputs
     * @return 待 {@link DefaultNodeScope#bind} 的输入
     */
    static Map<String, Sourced<?>> resolve(TurnScope turn, String nodeId, @Nullable SkillContract contract,
                                           Map<String, Map<String, Sourced<?>>> upstreamOutputs) {
        Map<String, Sourced<?>> bound = new LinkedHashMap<>();
        if (Objects.isNull(contract) || Collections.isEmpty(contract.getInputs())) {
            return bound;
        }
        var graphNode = turn.graph().find(nodeId);
        List<String> predecessors = Objects.isNull(graphNode) ? List.of() : List.copyOf(graphNode.getDependsOn());
        contract.getInputs().forEach((name, spec) -> {
            if (Strings.isBlank(name)) {
                return;
            }
            Sourced<?> value = resolveOne(turn, name, spec, predecessors, upstreamOutputs);
            if (Objects.nonNull(value)) {
                bound.put(name, value);
            }
        });
        return bound;
    }

    /**
     * 按 {@code from} 前缀从会话共享、任务共享、任务前情或上游 outputs 取值。
     *
     * @param turn            本轮视图
     * @param name            输入键
     * @param spec            该键的契约声明
     * @param predecessors    上游节点 id，近端在后
     * @param upstreamOutputs 已完成上游的 outputs
     * @return 带源值；解析不到时为 {@code null}
     */
    private static @Nullable Sourced<?> resolveOne(TurnScope turn, String name, InputSpec spec, List<String> predecessors,
                                                   Map<String, Map<String, Sourced<?>>> upstreamOutputs) {
        String from = Strings.trim(spec.getFrom());
        if (Strings.isBlank(from)) {
            return nearestUpstream(name, predecessors, upstreamOutputs);
        }
        if (from.startsWith("session.")) {
            return turn.sessionShared().get(from.substring("session.".length()));
        }
        if (from.startsWith("shared.")) {
            return turn.shared().get(from.substring("shared.".length()));
        }
        if (from.startsWith("prior.")) {
            return priorValue(turn, from.substring("prior.".length()));
        }
        if (from.startsWith("outputs.")) {
            return nearestUpstream(from.substring("outputs.".length()), predecessors, upstreamOutputs);
        }
        return nearestUpstream(name, predecessors, upstreamOutputs);
    }

    /**
     * 从最近的已完成上游节点取同名 output，来源记为 {@link SourceType#NODE_OUTPUT}。
     *
     * @param name            output 键
     * @param predecessors    上游节点 id，近端在后
     * @param upstreamOutputs 已完成上游的 outputs
     * @return 带源值；没有任何上游写出该键时为 {@code null}
     */
    private static @Nullable Sourced<?> nearestUpstream(String name, List<String> predecessors, Map<String, Map<String, Sourced<?>>> upstreamOutputs) {
        for (int i = predecessors.size() - 1; i >= 0; i--) {
            Map<String, Sourced<?>> outputs = upstreamOutputs.get(predecessors.get(i));
            if (Collections.isEmpty(outputs) || !outputs.containsKey(name)) {
                continue;
            }
            Sourced<?> sourced = outputs.get(name);
            if (Objects.isNull(sourced)) {
                continue;
            }
            return new Sourced<>(sourced.value(), new Source(SourceType.NODE_OUTPUT, predecessors.get(i), name));
        }
        return null;
    }

    /**
     * 从任务前情实体索引取值；路径为 {@code type} 或 {@code type.attr}，取该类型最新的实体。
     *
     * <p>来源记为 {@code prior.<type>.<attr>}，与参数绑定一致，来源校验在工作树 prior 袋上按同一路径复核。</p>
     *
     * @param turn 本轮视图
     * @param path 去掉 {@code prior.} 后的路径
     * @return 带源值；任务前情或实体缺失时为 {@code null}
     */
    private static @Nullable Sourced<?> priorValue(TurnScope turn, String path) {
        int dot = path.indexOf('.');
        String type = dot < 0 ? path : path.substring(0, dot);
        String attr = dot < 0 ? DISPLAY_NAME : path.substring(dot + 1);
        return turn.prior().flatMap(prior -> prior.entities().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(type + ":"))
                .reduce((_, last) -> last)
                .map(entry -> toSourced(type, entry.getKey(), entry.getValue(), attr)))
            .orElse(null);
    }

    /**
     * 把任务前情实体转成带源值。
     *
     * @param type  实体类型
     * @param key   实体索引键，如 {@code type:id}
     * @param entry 任务前情实体
     * @param attr  属性名：{@code id}、{@code displayName} 或实体属性
     * @return 带源值；属性不存在时为 {@code null}
     */
    private static @Nullable Sourced<?> toSourced(String type, String key, EntityEntry entry, String attr) {
        Object value;
        if (ID.equals(attr)) {
            value = key.substring(key.indexOf(':') + 1);
        } else if (DISPLAY_NAME.equals(attr)) {
            value = entry.displayName();
        } else {
            JsonNode at = entry.attrs().get(attr);
            if (!JSON.hasValue(at)) {
                return null;
            }
            if (at.isBoolean()) {
                value = at.booleanValue();
            } else if (at.isNumber()) {
                value = at.numberValue();
            } else {
                value = at.asString();
            }
        }
        if (Objects.isNull(value)) {
            return null;
        }
        return new Sourced<>(value, new Source(SourceType.PRIOR_TASK, type, type + "." + attr));
    }
}
