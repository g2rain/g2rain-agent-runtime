package com.g2rain.agent.runtime.support;


import com.g2rain.agent.runtime.spec.ArgSpec;
import com.g2rain.agent.runtime.spec.ToolSpec;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具入参的脱敏摘要。
 *
 * <p>输出仍是合法 JSON，先按契约屏蔽敏感字段，再截短字符串叶子，最后折叠过大的对象或数组。
 * 供证据头、交互历史回放和客户端 {@code argsBrief} 使用；不改调用方传入的原始节点。</p>
 *
 * <pre>{@code
 * String brief = ArgsBrief.of(args, toolSpec, 512);
 * // 敏感字段已是 "***"；超长则截短或收成 "{}"
 * }</pre>
 *
 * @author alpha
 * @since 2026/10/4
 */
public final class ArgsBrief {

    /**
     * 把摘要节点序列化为 JSON 字符串的编解码器。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 敏感值的替换文本：参数摘要、提示词中统一使用。
     */
    public static final String MASK = "***";

    /**
     * 入参摘要工具；禁止实例化。
     */
    private ArgsBrief() {

    }

    /**
     * 生成不超过上限的脱敏 JSON 摘要。
     *
     * <p>入参不是对象时包进 {@code value} 键。无契约则只做合法 JSON 裁剪。
     * 仍超长时逐步截短字符串、折叠容器，最后可退回 {@code {}}。</p>
     *
     * <pre>{@code
     * String brief = ArgsBrief.of(JSON.str2node("{\"token\":\"a\",\"q\":\"hi\"}"), spec, 64);
     * }</pre>
     *
     * @param args     原始入参；为空或非对象时按空对象或 {@code value} 包装处理
     * @param contract 工具契约；为空则不屏蔽敏感字段
     * @param maxChars 总长上限；小于 1 时按 1 处理
     * @return 合法 JSON 字符串，至少 {@code {}}
     */
    public static String of(@Nullable JsonNode args, @Nullable ToolSpec contract, int maxChars) {
        return JsonClip.of(masked(args, contract), maxChars);
    }

    /**
     * 脱敏后的入参副本，不裁剪长度；供需要逐字段读取的调用方直接使用，免去先序列化再解析。
     *
     * <pre>{@code
     * ObjectNode masked = ArgsBrief.masked(pending.args(), spec);
     * }</pre>
     *
     * @param args     原始入参；为空或非对象时按空对象或 {@code value} 包装处理
     * @param contract 工具契约；为空则不屏蔽敏感字段
     * @return 脱敏后的对象副本
     */
    public static ObjectNode masked(@Nullable JsonNode args, @Nullable ToolSpec contract) {
        ObjectNode root = copyObject(args);
        maskSensitive(root, contract);
        return root;
    }

    /**
     * 把入参深拷贝成对象节点，避免改写调用方持有的树。
     *
     * @param args 原始入参；非对象时写入 {@code value}
     * @return 可就地脱敏和裁剪的对象副本
     */
    private static ObjectNode copyObject(@Nullable JsonNode args) {
        if (args instanceof ObjectNode object) {
            return object.deepCopy();
        }

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        if (JSON.hasValue(args)) {
            // 非对象入参包进 value，后续脱敏仍走对象树
            root.set("value", args.deepCopy());
        }

        return root;
    }

    /**
     * 按契约把已存在的敏感字段写成 {@code ***}。
     *
     * <p>展开 {@code []} 通配后逐项覆盖，不凭空补键。</p>
     *
     * @param root     待脱敏的对象副本
     * @param contract 工具契约；为空则直接返回
     */
    private static void maskSensitive(ObjectNode root, @Nullable ToolSpec contract) {
        // 无契约则不屏蔽，只做后续长度裁剪
        if (Objects.isNull(contract) || Collections.isEmpty(contract.getArgs())) {
            return;
        }

        JsonNode mask = JsonNodeFactory.instance.stringNode(MASK);
        for (Map.Entry<String, ArgSpec> entry : contract.getArgs().entrySet()) {
            ArgSpec spec = entry.getValue();
            if (Objects.isNull(spec) || !spec.isSensitive() || Strings.isBlank(entry.getKey())) {
                continue;
            }

            for (String concrete : expandWildcardPaths(root, entry.getKey().trim())) {
                JsonNode existing = JSON.lookupNode(root, concrete);
                if (!JSON.hasValue(existing)) {
                    continue;
                }

                JSON.upsertNode(root, concrete, mask, false);
            }
        }
    }

    /**
     * 把契约路径中的 {@link RuntimeUtils#WILDCARD_ARRAY_INDEX} 按实际数组长度展开。
     *
     * <p>例：{@code items[].name} 且 items 有两项 → {@code items[0].name}、{@code items[1].name}。</p>
     *
     * @param root     入参树
     * @param template 含 {@code []} 的契约路径
     * @return 可直接 lookup 的具体路径；对应数组为空时该项被跳过
     */
    private static List<String> expandWildcardPaths(JsonNode root, String template) {
        List<String> paths = new ArrayList<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(template);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            int wild = current.indexOf(RuntimeUtils.WILDCARD_ARRAY_INDEX);
            if (wild < 0) {
                // 已无 [] 通配，当前片段就是可 lookup 的具体路径
                paths.add(current);
                continue;
            }

            String prefix = current.substring(0, wild);
            String suffix = current.substring(wild + RuntimeUtils.WILDCARD_ARRAY_INDEX.length());
            JsonNode array = Strings.isBlank(prefix) ? root : JSON.lookupNode(root, prefix);
            if (!(array instanceof ArrayNode arr) || arr.isEmpty()) {
                // 通配对不上实际数组时跳过，避免凭空生成路径
                continue;
            }

            for (int i = 0, n = arr.size(); i < n; i++) {
                pending.addLast(prefix + "[" + i + "]" + suffix);
            }
        }

        return paths;
    }
}
