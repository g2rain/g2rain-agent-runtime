package com.g2rain.agent.runtime.support;


import com.g2rain.agent.runtime.domain.hydrate.ArgSpec;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
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
     * 敏感字段替换值。
     */
    private static final String MASK = "***";

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
        int max = Math.max(1, maxChars);
        ObjectNode root = copyObject(args);
        maskSensitive(root, contract);
        String json = JSON.obj2str(root);
        if (Strings.isBlank(json)) {
            // 序列化失败也给合法 JSON，避免上游拼出空 brief
            return "{}";
        }

        // 已在上限内则不再截短，保留脱敏后的完整结构
        if (json.length() <= max) {
            return json;
        }

        // 先截最长字符串叶子，尽量保住对象结构
        shrinkStringLeaves(root, max);
        json = JSON.obj2str(root);
        if (json.length() <= max) {
            return json;
        }

        // 字符串截短仍超长时折叠最大容器，保证输出始终是合法 JSON
        return collapseToFit(root, max);
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

    /**
     * 保留键名，把最长的字符串叶子逐步截短，直到序列化长度不超过上限。
     *
     * @param root 就地修改的对象副本
     * @param max  序列化长度上限
     */
    private static void shrinkStringLeaves(ObjectNode root, int max) {
        for (int guard = 0; guard < 64; guard++) {
            String json = JSON.obj2str(root);
            if (Strings.isBlank(json) || json.length() <= max) {
                return;
            }

            List<StringLeaf> leaves = collectStringLeaves(root);
            // 没有字符串叶子可截则停，避免空转
            if (leaves.isEmpty()) {
                return;
            }

            leaves.sort((a, b) -> Integer.compare(b.value().length(), a.value().length()));
            StringLeaf longest = leaves.getFirst();
            int excess = json.length() - max;
            int keep = Math.max(0, longest.value().length() - excess - 1);
            setAt(root, longest.path(), JsonNodeFactory.instance.stringNode(
                keep == 0 ? "" : longest.value().substring(0, keep)
            ));
        }
    }

    /**
     * 仍超长时把最大的非标量节点收成空对象或空数组，保证合法 JSON。
     *
     * @param root 就地修改的对象副本
     * @param max  序列化长度上限
     * @return 不超过上限的 JSON；无法再折则 {@code {}}
     */
    private static String collapseToFit(ObjectNode root, int max) {
        for (int guard = 0; guard < 32; guard++) {
            String json = JSON.obj2str(root);
            if (Strings.isBlank(json)) {
                return "{}";
            }

            if (json.length() <= max) {
                return json;
            }

            List<ContainerLeaf> containers = collectContainers(root);
            // 没有可折叠容器则退回空对象，保证输出仍是合法 JSON
            if (containers.isEmpty()) {
                return "{}";
            }

            containers.sort((a, b) -> Integer.compare(b.size(), a.size()));
            ContainerLeaf biggest = containers.getFirst();
            setAt(root, biggest.path(), biggest.array()
                ? JsonNodeFactory.instance.arrayNode()
                : JsonNodeFactory.instance.objectNode());
        }

        String json = JSON.obj2str(root);
        // 折叠次数用尽仍超长则退回空对象，保证调用方拿到合法 JSON
        return Strings.isBlank(json) || json.length() > max ? "{}" : json;
    }

    /**
     * 深度收集字符串叶子，供截短时挑选最长项。
     *
     * @param root 当前树
     * @return 路径与字符串值
     */
    private static List<StringLeaf> collectStringLeaves(JsonNode root) {
        List<StringLeaf> leaves = new ArrayList<>();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, ""));
        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            JsonNode node = frame.node();
            if (node.isString()) {
                leaves.add(new StringLeaf(frame.path(), node.asString()));
                continue;
            }
            if (node.isObject()) {
                for (var field : node.properties()) {
                    String child = Strings.isBlank(frame.path()) ? field.getKey() : frame.path() + "." + field.getKey();
                    stack.push(new Frame(field.getValue(), child));
                }
            } else if (node.isArray()) {
                for (int i = 0, n = node.size(); i < n; i++) {
                    String child = frame.path() + "[" + i + "]";
                    stack.push(new Frame(node.get(i), child));
                }
            }
        }
        return leaves;
    }

    /**
     * 深度收集非根的非空对象或数组，供折叠时挑选最大项。
     *
     * @param root 当前树
     * @return 路径、子项数量和是否数组
     */
    private static List<ContainerLeaf> collectContainers(JsonNode root) {
        List<ContainerLeaf> containers = new ArrayList<>();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, ""));
        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            JsonNode node = frame.node();
            if (node.isObject()) {
                if (Strings.isNotBlank(frame.path()) && !node.isEmpty()) {
                    containers.add(new ContainerLeaf(frame.path(), node.size(), false));
                }

                for (var field : node.properties()) {
                    String child = Strings.isBlank(frame.path()) ? field.getKey() : frame.path() + "." + field.getKey();
                    stack.push(new Frame(field.getValue(), child));
                }
            } else if (node.isArray()) {
                if (Strings.isNotBlank(frame.path()) && !node.isEmpty()) {
                    containers.add(new ContainerLeaf(frame.path(), node.size(), true));
                }

                for (int i = 0, n = node.size(); i < n; i++) {
                    stack.push(new Frame(node.get(i), frame.path() + "[" + i + "]"));
                }
            }
        }

        return containers;
    }

    /**
     * 按路径覆盖节点；空路径不写，避免把整棵树换成标量。
     *
     * @param root  对象根
     * @param path  点/下标路径
     * @param value 新值
     */
    private static void setAt(ObjectNode root, String path, JsonNode value) {
        // 空路径不覆盖根，避免把整棵树换成标量后无法再当对象摘要
        if (Strings.isBlank(path)) {
            return;
        }

        JSON.upsertNode(root, path, value, true);
    }

    /**
     * 遍历帧：当前节点及其路径。
     */
    private record Frame(JsonNode node, String path) {

    }

    /**
     * 字符串叶子：路径与原文。
     */
    private record StringLeaf(String path, String value) {

    }

    /**
     * 可折叠容器：路径、子项数、是否数组。
     */
    private record ContainerLeaf(String path, int size, boolean array) {

    }
}
