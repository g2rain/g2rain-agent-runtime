package com.g2rain.agent.runtime.support;


import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContextHolder;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Runtime 共用的无状态小工具。
 *
 * <p>覆盖无连字符 UUID、traceId、路径叶子键、文本截断、时间戳转换、从自由文本抠 JSON、SHA-256、工具入参摘要，
 * 以及参数路径上的数组通配符 {@link #WILDCARD_ARRAY_INDEX}。本类不访问 Redis 或会话状态。</p>
 *
 * <pre>{@code
 * String id = RuntimeUtils.uuid();
 * String json = RuntimeUtils.extractObject(modelText);
 * String hash = RuntimeUtils.argsHash(args);
 * }</pre>
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class RuntimeUtils {

    /**
     * 参数路径中的「数组任意下标」通配符，字面量为 {@code []}。
     * <p>
     * 例：任务记忆路径 {@code items[].name} 表示 items 数组每一项的 name 字段。
     */
    public static final String WILDCARD_ARRAY_INDEX = "[]";

    /**
     * 末级叶子键：最后一个点分段，剥掉尾部的 {@code []} 或 {@code [n]}。
     */
    private static final Pattern LEAF_KEY = Pattern.compile(
        "(?:.*\\.)?([^.\\[\\]]+)(?:" + Pattern.quote(WILDCARD_ARRAY_INDEX) + "|\\[\\d+])*$"
    );

    /**
     * 截断后缀。
     */
    private static final String ELLIPSIS = "…";

    /**
     * JSON 编解码；入参摘要排序走 {@link JsonCodec#sort(byte[])}。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 匹配 Markdown 代码块：可选语言标记 json，捕获块内正文。
     * <p>
     * 例：语言标记为 json 或省略语言的三反引号代码块，捕获块内正文。
     */
    private static final Pattern JSON_BLOCK = Pattern.compile(
        "```\\s*(?:json)?\\s*([\\s\\S]*?)```",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * JSON 起止括号配对表：对象用花括号，数组用方括号。
     */
    private static final Map<Character, Character> JSON_PAIRS = Map.of(
        '{', '}',
        '[', ']'
    );

    /**
     * 工具类禁止实例化。
     */
    private RuntimeUtils() {

    }

    /**
     * 生成 32 位无连字符 UUID 字符串。
     * <p>
     * 例：{@code a1b2c3d4e5f64789a0b1c2d3e4f50617}，常用于 conversationId / taskId。
     *
     * @return 去连字符后的 UUID
     */
    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 取当前请求链路的 traceId；上下文没有则现场生成一个。
     *
     * <pre>{@code
     * String traceId = RuntimeUtils.requireTraceId();
     * }</pre>
     *
     * @return 非空白的 traceId
     */
    public static String requireTraceId() {
        String traceId = PrincipalContextHolder.getTraceId();
        // 请求上下文没有 trace 时现场补一个，保证调用方总能拿到值
        return Strings.isBlank(traceId) ? uuid() : traceId;
    }

    /**
     * 取路径末级叶子键，剥掉数组下标。
     *
     * <pre>{@code
     * RuntimeUtils.leafKey("buyer.name");    // "name"
     * RuntimeUtils.leafKey("items[].sku");   // "sku"
     * RuntimeUtils.leafKey("a.tags[0]");     // "tags"
     * RuntimeUtils.leafKey(null);            // ""
     * }</pre>
     *
     * @param path 参数或事实路径；可空
     * @return 叶子键；空路径或无法解析为空串
     */
    public static String leafKey(@Nullable String path) {
        if (Strings.isBlank(path)) {
            return "";
        }

        Matcher matcher = LEAF_KEY.matcher(path.trim());
        return matcher.matches() ? matcher.group(1) : "";
    }

    /**
     * 按字符数截断。
     *
     * <pre>{@code
     * RuntimeUtils.clip("abcdef", 3); // "abc"
     * RuntimeUtils.clip("abc", 0);    // "abc"
     * }</pre>
     *
     * @param text 原文；可空
     * @param max  最大字符数；小于等于 0 不截断
     * @return 截断结果；{@code null} 入参仍为 {@code null}
     */
    public static @Nullable String clip(@Nullable String text, int max) {
        if (Objects.isNull(text) || max <= 0 || text.length() <= max) {
            return text;
        }

        return text.substring(0, max);
    }

    /**
     * 还原模型在 JSON 字符串里多转义一次的换行。
     *
     * <p>只在文本没有任何真实换行、却含字面 {@code \n} 时还原 {@code \r\n}、{@code \n}、{@code \t}；
     * 已有真实换行的多行文本（含代码块）原样返回。</p>
     *
     * <pre>{@code
     * RuntimeUtils.unescapeLiteral("第一段\\n\\n第二段"); // "第一段\n\n第二段"
     * RuntimeUtils.unescapeLiteral("a\nb\\n");           // 原样返回
     * }</pre>
     *
     * @param text 模型给出的文案
     * @return 还原后的文案
     */
    public static String unescapeLiteral(String text) {
        if (text.indexOf('\n') >= 0 || !text.contains("\\n")) {
            return text;
        }

        return text.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\t", "\t");
    }

    /**
     * 按字符数截断，被截断时以 {@code …} 结尾。
     *
     * <pre>{@code
     * RuntimeUtils.abbreviate("abcdef", 3); // "abc…"
     * }</pre>
     *
     * @param text 原文；可空
     * @param max  保留字符数；小于等于 0 不截断
     * @return 截断结果；{@code null} 入参仍为 {@code null}
     */
    public static @Nullable String abbreviate(@Nullable String text, int max) {
        if (Objects.isNull(text) || max <= 0 || text.length() <= max) {
            return text;
        }

        return text.substring(0, max) + ELLIPSIS;
    }

    /**
     * JSON 节点转普通 Java 值：字符串、数字、布尔、Map 或 List。
     *
     * <pre>{@code
     * RuntimeUtils.plain(StringNode.valueOf("21668")); // "21668"
     * RuntimeUtils.plain(NullNode.getInstance());       // null
     * }</pre>
     *
     * @param node JSON 节点；可空
     * @return 普通值；空或 JSON null 为 {@code null}
     */
    public static @Nullable Object plain(@Nullable JsonNode node) {
        if (Objects.isNull(node) || node.isNull() || node.isMissingNode()) {
            return null;
        }

        return JSON.str2obj(JSON.obj2str(node), Object.class);
    }

    /**
     * 工具入参文本转 JSON 节点。
     *
     * @param arguments 原始入参；空白或非法按 {@code {}}
     * @return 非空 JSON 节点
     */
    public static JsonNode argsNode(@Nullable String arguments) {
        JsonNode node = JSON.str2node(Strings.defaultIfBlank(arguments, "{}"));
        return Objects.isNull(node) ? JSON.str2node("{}") : node;
    }

    /**
     * 本地时间转毫秒时间戳（系统时区）。
     *
     * @param time 本地时间
     * @return 毫秒时间戳
     */
    public static long epochOf(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 毫秒时间戳转本地时间（系统时区）。
     *
     * @param epochMs 毫秒时间戳
     * @return 本地时间
     */
    public static LocalDateTime timeOf(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }

    /**
     * 从模型/助手自由文本中尽量抽出一段 JSON 对象或数组字面量。
     * <p>
     * 优先剥 Markdown 代码块；再找首个对象/数组左括号起点，用配对右括号截到最后一次出现处。
     * 例：前缀闲聊 + {@code {"ok":true}} + 后缀 → 返回 {@code {"ok":true}}；找不到括号则返回去空白后的原文。
     *
     * @param content 可能夹杂说明文字的模型输出；{@code null} 按空串处理
     * @return 抽出的 JSON 片段，或无法定位时的原文
     */
    public static String extractObject(String content) {
        String text = Objects.toString(content, "").trim();
        Matcher matcher = JSON_BLOCK.matcher(text);
        if (matcher.find()) {
            // 命中 Markdown 代码块则改扫块内正文
            text = matcher.group(1).trim();
        }

        String scanText = text;
        int start = IntStream.range(0, text.length())
            .filter(i -> isJsonStart(scanText.charAt(i)))
            .findFirst().orElse(-1);

        // 找不到对象/数组起点则退回原文，不伪造 JSON
        if (start < 0) {
            return text;
        }

        char endChar = JSON_PAIRS.get(text.charAt(start));
        int end = text.lastIndexOf(endChar);
        // 结束符必须在起点之后才截闭区间，否则退回原文
        return end > start ? text.substring(start, end + 1) : text;
    }

    /**
     * 判断字符是否为 JSON 字面量起点（对象或数组左括号）。
     *
     * @param ch 待测字符
     * @return 是 '{' 或 '[' 则为 true
     */
    private static boolean isJsonStart(char ch) {
        return JSON_PAIRS.containsKey(ch);
    }

    /**
     * 计算 SHA-256 并返回小写十六进制。
     * <pre>{@code
     * String hex = RuntimeUtils.sha256Hex("abc".getBytes(StandardCharsets.UTF_8));
     * }</pre>
     *
     * @param bytes 原文
     * @return 64 位十六进制
     */
    public static String sha256Hex(byte[] bytes) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 必须可用；缺失算法视为运行时损坏而不是业务失败
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 计算工具参数摘要，用于确认绑定与写操作去重。
     * <p>入参为 {@link JsonNode}；按键名排序后序列化，取 SHA-256 的前 16 位十六进制。参数为空时按空对象计算。</p>
     * <pre>{@code
     * JsonNode args = JSON.str2node("{\"amount\":100,\"buyer\":\"百望\"}");
     * String hash = RuntimeUtils.argsHash(args);
     * // 返回 16 位十六进制；对象字段顺序不同，结果相同
     * }</pre>
     *
     * @param args 工具参数；可为 null / JSON null
     * @return 16 位十六进制摘要
     */
    public static String argsHash(@Nullable JsonNode args) {
        // 空入参按空对象摘要，保证确认绑定总能算出稳定 hash
        JsonNode node = Objects.isNull(args) || args.isNull() ? JSON.str2node("{}") : args;
        String sorted = JSON.sort(JSON.obj2byte(node));
        return sha256Hex(sorted.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    /**
     * 从异常链取出 {@link BusinessException} 的对外错误码。
     *
     * <p>沿 cause 向下找第一个业务异常；没有则返回 {@code null}，调用方应改用通用错误码。</p>
     * <pre>{@code
     * String code = RuntimeUtils.businessCode(ex);
     * turn.fail(message, code);
     * }</pre>
     *
     * @param ex 任意异常；可空
     * @return 业务错误码；链上没有业务异常则为 {@code null}
     */
    public static @Nullable String businessCode(@Nullable Throwable ex) {
        Throwable current = ex;
        while (Objects.nonNull(current)) {
            if (current instanceof BusinessException business) {
                return business.getErrorCode();
            }

            // 包装异常只认 cause 链上的业务码，不把中间层类名当错误码
            current = current.getCause();
        }

        return null;
    }
}
