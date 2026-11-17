package com.g2rain.agent.runtime.support;


import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 保结构裁剪 JSON：超出上限时先截短最长的字符串叶子，仍超长再折叠最大的对象或数组，输出始终是合法 JSON。
 *
 * <p>长文本（正文、描述）先被截短，键名与 id、名称、状态这类短字段尽量原样保留。不改调用方传入的节点。</p>
 *
 * <pre>{@code
 * String text = JsonClip.of(payload, 8000);
 * }</pre>
 *
 * @author alpha
 * @since 2026/11/16
 */
public final class JsonClip {

    /**
     * 序列化与按路径改写节点。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 包装键：把任意根节点放进对象里统一按路径改写，输出时再取出。
     */
    private static final String HOLDER = "v";

    /**
     * 截短字符串叶子的最多轮数。
     */
    private static final int SHRINK_ROUNDS = 64;

    /**
     * 折叠容器的最多轮数。
     */
    private static final int COLLAPSE_ROUNDS = 32;

    /**
     * 静态工具，禁止实例化。
     */
    private JsonClip() {

    }

    /**
     * 不超过上限时原样序列化；超出则保结构裁剪。
     *
     * @param node     待裁剪节点
     * @param maxChars 序列化长度上限；小于 1 时按 1 处理
     * @return 合法 JSON；对象根折叠到底为 {@code {}}，数组根为 {@code []}
     */
    public static String of(JsonNode node, int maxChars) {
        int max = Math.max(1, maxChars);
        String json = JSON.obj2str(node);
        if (json.length() <= max) {
            return json;
        }

        ObjectNode holder = JsonNodeFactory.instance.objectNode();
        holder.set(HOLDER, node.deepCopy());
        // 包装对象比原值多出 {"v":} 这几个字符，上限同步放宽
        int budget = max + JSON.obj2str(holder).length() - json.length();
        shrinkStringLeaves(holder, budget);
        collapseToFit(holder, budget);
        String clipped = JSON.obj2str(holder.get(HOLDER));
        if (clipped.length() <= max) {
            return clipped;
        }

        return node.isArray() ? "[]" : "{}";
    }

    /**
     * 保留键名，把最长的字符串叶子逐步截短，直到序列化长度不超过上限。
     *
     * @param root 就地修改的包装对象
     * @param max  序列化长度上限
     */
    private static void shrinkStringLeaves(ObjectNode root, int max) {
        for (int guard = 0; guard < SHRINK_ROUNDS; guard++) {
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
     * @param root 就地修改的包装对象
     * @param max  序列化长度上限
     */
    private static void collapseToFit(ObjectNode root, int max) {
        for (int guard = 0; guard < COLLAPSE_ROUNDS; guard++) {
            String json = JSON.obj2str(root);
            if (Strings.isBlank(json) || json.length() <= max) {
                return;
            }

            List<ContainerLeaf> containers = collectContainers(root);
            // 没有可折叠容器则停，交由调用方兜底
            if (containers.isEmpty()) {
                return;
            }

            containers.sort((a, b) -> Integer.compare(b.size(), a.size()));
            ContainerLeaf biggest = containers.getFirst();
            setAt(root, biggest.path(), biggest.array()
                ? JsonNodeFactory.instance.arrayNode()
                : JsonNodeFactory.instance.objectNode());
        }
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
