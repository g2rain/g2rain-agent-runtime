package com.g2rain.agent.runtime.engine.tools.guard;

import com.g2rain.agent.runtime.domain.hydrate.ValueRef;
import com.g2rain.agent.runtime.domain.state.Platform;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.task.EntityEntry;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Objects;

/**
 * 把当前节点 inputs / outputs、任务共享、会话共享、任务前情与平台身份收成一棵 JSON，供绑定与来源校验查找。
 *
 * @author alpha
 * @since 2026/10/3
 */
final class WorkingTree {

    /**
     * JSON 编解码。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 静态工作树工具，禁止实例化。
     */
    private WorkingTree() {

    }

    /**
     * 组装工作树。
     *
     * @param ctx 当前工具执行上下文
     * @return {@code {inputs, outputs, shared, session, prior, platform}}
     */
    static JsonNode of(ToolExecutionContext ctx) {
        ObjectNode root = (ObjectNode) JSON.str2node("{}");
        root.set(ValueRef.Bag.INPUTS.wirePrefix(), bag(ctx.scope().inputs()));
        root.set(ValueRef.Bag.OUTPUTS.wirePrefix(), bag(ctx.scope().outputs()));
        root.set(ValueRef.Bag.SHARED.wirePrefix(), bag(ctx.getFrame().turn().shared()));
        root.set(ValueRef.Bag.SESSION.wirePrefix(), bag(ctx.getFrame().turn().sessionShared()));
        root.set(ValueRef.Bag.PRIOR.wirePrefix(), priorBag(ctx));
        Platform platform = ctx.getFrame().turn().platform();
        root.set(ValueRef.Bag.PLATFORM.wirePrefix(), platform.toJson());
        return root;
    }

    /**
     * 按 {@link ValueRef} 在 WorkingTree 上取值。
     *
     * @param working 取值树
     * @param ref     值引用
     * @return 取到的值；路径为空、引用证据或取不到时为 {@code null}
     */
    static @Nullable JsonNode lookup(JsonNode working, ValueRef ref) {
        if (Strings.isBlank(ref.path())) {
            return null;
        }

        if (ref.bag() == ValueRef.Bag.EVIDENCE) {
            return null;
        }

        JsonNode bagNode = working.get(ref.bag().wirePrefix());
        if (Objects.isNull(bagNode)) {
            return null;
        }

        JsonNode at = JSON.lookupNode(bagNode, ref.path());
        return JSON.hasValue(at) ? at : null;
    }

    /**
     * 带源 Map 转 JSON 对象。
     *
     * @param sourced 带源值
     * @return 对象节点；空 Map 为空对象
     */
    private static JsonNode bag(Map<String, Sourced<?>> sourced) {
        ObjectNode node = (ObjectNode) JSON.str2node("{}");
        sourced.forEach((key, value) -> {
            if (Strings.isBlank(key)) {
                return;
            }

            JsonNode json = JSON.obj2node(value.value());
            if (Objects.nonNull(json)) {
                node.set(key, json);
            }
        });
        return node;
    }

    /**
     * 任务前情实体转工作树 prior 袋。
     *
     * <p>每个实体以索引键 {@code type:id} 收录，同时以类型名收录最新的一个，所以
     * {@code prior.space:19607.id} 与 {@code prior.space.id}（最近的空间）都能取到值。</p>
     *
     * @param ctx 执行上下文
     * @return prior 对象；无前情为空对象
     */
    private static JsonNode priorBag(ToolExecutionContext ctx) {
        ObjectNode node = (ObjectNode) JSON.str2node("{}");
        ctx.getFrame().turn().prior().ifPresent(prior -> prior.entities().forEach((key, entry) -> {
            int colon = key.indexOf(':');
            if (colon <= 0 || colon == key.length() - 1) {
                return;
            }

            JsonNode json = priorEntityNode(key.substring(colon + 1), entry);
            node.set(key, json);
            // 按插入顺序覆盖：类型名指向最新的同类实体
            node.set(key.substring(0, colon), json);
        }));
        return node;
    }

    /**
     * 任务前情实体转 JSON：id、展示名加扁平属性。
     *
     * @param id    实体 id（取自索引键）
     * @param entry 实体
     * @return 对象节点
     */
    private static JsonNode priorEntityNode(String id, EntityEntry entry) {
        ObjectNode node = (ObjectNode) JSON.str2node("{}");
        JsonNode attrs = entry.attrs();
        if (JSON.hasValue(attrs) && attrs.isObject()) {
            attrs.properties().forEach(field -> node.set(field.getKey(), field.getValue()));
        }

        node.put("id", id);
        if (Strings.isNotBlank(entry.displayName())) {
            node.put("displayName", entry.displayName());
        }
        return node;
    }
}
