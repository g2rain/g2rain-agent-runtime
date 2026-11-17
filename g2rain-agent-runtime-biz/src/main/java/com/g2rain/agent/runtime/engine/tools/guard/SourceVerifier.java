package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.spec.ValueRef;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.text.Normalizer;
import java.util.BitSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 来源校验的唯一实现：按完整路径精确比对，不做证据树子串扫描。
 *
 * <p>用户口头确认可按配置窗口比对交互历史；其它来源走工作树或证据报文。</p>
 *
 * @author alpha
 * @since 2026/10/4
 */
@Component
public class SourceVerifier {

    /**
     * JSON 编解码。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 「用户」来源按包含判断时值的最短长度；更短的值（如「1」「是」）几乎出现在任何消息里，不足以证明来自用户。
     */
    private static final int USER_VALUE_MIN_LENGTH = 2;

    /**
     * ASCII 字符上界（不含）。
     */
    private static final int ASCII_LIMIT = 128;

    /**
     * 证据读取：校验 EVIDENCE 来源的值。
     */
    private final EvidenceReader evidenceReader;

    /**
     * 运行时配置：对话窗口上限。
     */
    private final RuntimeProperties properties;

    /**
     * 创建来源校验器。
     *
     * @param evidenceReader 证据精确取值
     * @param properties     运行时配置，含交互历史窗口
     */
    public SourceVerifier(EvidenceReader evidenceReader, RuntimeProperties properties) {
        this.evidenceReader = evidenceReader;
        this.properties = properties;
    }

    /**
     * 校验声明来源是否支撑该值。
     *
     * <p>按完整路径精确比对。USER 查交互历史；SESSION 查会话记忆；PRIOR_TASK 查任务前情；
     * EVIDENCE 查证据报文；CONTRACT 直接通过；MODEL 一律不通过。</p>
     *
     * @param source 声明来源
     * @param value  待核验值；可空
     * @param ctx    当前工具执行上下文
     * @return 来源能支撑该值为 {@code true}
     */
    public boolean verify(Source source, @Nullable Object value, ToolExecutionContext ctx) {
        JsonNode node = JSON.obj2node(value);
        return switch (source.type()) {
            case EVIDENCE -> evidenceReader.matches(ctx, source.ref(), source.path(), node);
            case USER -> matchesUser(source, node, ctx);
            case NODE_OUTPUT -> matchesBagged(ctx.scope().outputs(), source, node)
                || matchesBagged(ctx.scope().inputs(), source, node);
            case SHARED -> matchesBagged(ctx.getFrame().turn().shared(), source, node);
            case SESSION -> matchesBagged(ctx.getFrame().turn().sessionShared(), source, node);
            case PRIOR_TASK -> {
                JsonNode working = WorkingTree.of(ctx);
                String key = Strings.defaultIfBlank(source.path(), source.ref());
                JsonNode actual = WorkingTree.lookup(working, new ValueRef(ValueRef.Bag.PRIOR, key));
                yield JSON.hasValue(actual) && JSON.nodesEqual(actual, node);
            }
            case PLATFORM -> matchesPlatform(source, node, ctx);
            case CONTRACT -> true;
            case MODEL -> false;
        };
    }

    /**
     * 校验已写入的带源值。
     *
     * @param sourced 带源值
     * @param ctx     当前工具执行上下文
     * @return 来源能支撑其值为 {@code true}
     */
    public boolean verify(Sourced<?> sourced, ToolExecutionContext ctx) {
        return verify(sourced.source(), sourced.value(), ctx);
    }

    /**
     * 无 Source 时：在交互历史里找能支撑该值的 USER messageId（规范化后包含）。
     *
     * @param ctx    当前工具执行上下文
     * @param argVal 待匹配的入参值
     * @return 命中的用户消息 id；找不到为空
     */
    public Optional<String> findUserMessageId(ToolExecutionContext ctx, JsonNode argVal) {
        String needle = normalizeText(JSON.normalizeNode(argVal));
        if (Strings.isBlank(needle) || needle.length() < USER_VALUE_MIN_LENGTH) {
            return Optional.empty();
        }

        for (var message : ctx.getFrame().turn().dialogue(properties.getState().getDialogueMax())) {
            if (message.getRole() != DialogueRole.USER) {
                continue;
            }

            String text = message.getText();
            if (!containsValue(text, needle)) {
                continue;
            }

            String messageId = message.getMessageId();
            if (Objects.isNull(messageId) || Strings.isBlank(messageId)) {
                continue;
            }

            return Optional.of(messageId);
        }
        return Optional.empty();
    }

    /**
     * 本轮已回答的 FOLLOW_UP 检查点的 expectedFields 是否已直接认定该参数路径。
     *
     * @param ctx       当前工具执行上下文
     * @param paramPath 参数路径
     * @return 本轮回答的检查点为追问且字段列表含该路径或叶子键
     */
    public boolean followUpAccepted(ToolExecutionContext ctx, String paramPath) {
        Optional<TaskCheckpoint> checkpoint = ctx.getFrame().turn().resumed();
        if (checkpoint.isEmpty() || checkpoint.get().getType() != CheckpointType.FOLLOW_UP) {
            return false;
        }

        var fields = checkpoint.get().getExpectedFields();
        if (fields.isEmpty()) {
            return false;
        }

        String leaf = RuntimeUtils.leafKey(paramPath);
        return fields.contains(paramPath) || fields.contains(leaf);
    }

    /**
     * 入参值是否等于任务前情中某个实体的 id：模型引用之前任务里的对象（如"刚建的空间"）时据此认定来源。
     *
     * @param ctx    当前工具执行上下文
     * @param argVal 待比对的入参值；可空
     * @param entity 参数指向的实体类型；配置时要求类型一致，未配置时只比对 id
     * @return 存在匹配的前情实体
     */
    public boolean matchesPriorId(ToolExecutionContext ctx, @Nullable JsonNode argVal, @Nullable String entity) {
        if (!JSON.hasValue(argVal) || !argVal.isValueNode()) {
            return false;
        }

        String text = argVal.asString().trim();
        return ctx.getFrame().turn().prior()
            .map(prior -> Strings.isNotBlank(entity)
                ? prior.entities().containsKey(entity.trim() + ":" + text)
                : prior.entities().keySet().stream()
                    .anyMatch(key -> key.indexOf(':') > 0 && text.equals(key.substring(key.indexOf(':') + 1))))
            .orElse(false);
    }

    /**
     * 入参值是否与某条成功证据精确相等（无子串匹配）。
     *
     * @param ctx    当前工具执行上下文
     * @param argVal 待比对的入参值
     * @param entity 参数指向的实体类型；配置时要求值等于该类型对象的标识，未配置时在 extract 子树中找相等节点
     * @return 证据中存在匹配
     */
    public boolean matchesEvidenceValue(ToolExecutionContext ctx, JsonNode argVal, @Nullable String entity) {
        return Strings.isNotBlank(entity)
            ? evidenceReader.matchesEntityId(ctx, entity.trim(), argVal)
            : evidenceReader.matchesExact(ctx, argVal);
    }

    /**
     * 值是否出现在指定用户消息里，或本轮追问已接受该路径。
     *
     * @param source 声明来源
     * @param value  入参值
     * @param ctx    执行上下文
     * @return 用户来源校验通过
     */
    private boolean matchesUser(Source source, JsonNode value, ToolExecutionContext ctx) {
        if (followUpAccepted(ctx, Strings.defaultIfBlank(source.path(), ""))) {
            return true;
        }

        String messageId = source.ref();
        if (Strings.isBlank(messageId)) {
            return false;
        }

        String needle = normalizeText(JSON.normalizeNode(value));
        if (Strings.isBlank(needle) || needle.length() < USER_VALUE_MIN_LENGTH) {
            return false;
        }

        return ctx.getFrame().turn().dialogue(properties.getState().getDialogueMax()).stream()
            .filter(message -> message.getRole() == DialogueRole.USER)
            .filter(message -> Objects.equals(messageId, message.getMessageId()))
            .anyMatch(message -> Strings.isNotBlank(message.getText())
                && containsValue(message.getText(), needle));
    }

    /**
     * 值是否与共享/会话/任务前情袋中带源值相等。
     *
     * @param bag      带源 Map
     * @param source   声明来源
     * @param expected 入参值
     * @return 按键查到且值相等
     */
    private boolean matchesBagged(Map<String, Sourced<?>> bag, Source source, JsonNode expected) {
        // 只按键精确核验：键查不到即不通过，不退化为「同类来源任一值相等」
        String key = Strings.defaultIfBlank(source.path(), source.ref());
        if (Strings.isBlank(key)) {
            return false;
        }

        Sourced<?> sourced = bag.get(key);
        if (Objects.isNull(sourced)) {
            return false;
        }

        return JSON.nodesEqual(JSON.obj2node(sourced.value()), expected)
            && (Strings.isBlank(source.ref())
            || Objects.equals(source.ref(), sourced.source().ref())
            || Objects.equals(source.ref(), key));
    }

    /**
     * 值是否等于本轮平台快照。
     *
     * @param source   声明来源
     * @param expected 入参值
     * @param ctx      执行上下文
     * @return 平台字段匹配
     */
    private boolean matchesPlatform(Source source, JsonNode expected, ToolExecutionContext ctx) {
        String key = Strings.defaultIfBlank(source.path(), source.ref());
        if (Strings.isBlank(key)) {
            return false;
        }

        return ctx.getFrame().turn().platform().matches(RuntimeUtils.leafKey(key), expected);
    }

    /**
     * 归一化文本用于宽松比对：NFKC 归一、全角空格转半角、去除空白并转小写。
     *
     * @param raw 原文；可空
     * @return 归一化文本；空白时为空串
     */
    static String normalizeText(@Nullable String raw) {
        if (Strings.isBlank(raw)) {
            return "";
        }

        String str = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        return str.replace('\u3000', ' ')
            .replaceAll("\\s+", "")
            .toLowerCase(Locale.ROOT);
    }

    /**
     * 用户原文是否包含该值：归一化后按包含判断，值的首尾是英文字母或数字时，命中处前后不能紧挨字母或数字。
     *
     * <p>例：「2012」中不认「12」；「订单 ORD-10086」中认「ord-10086」；中文没有词边界，「发布到官网吧」中认「官网」。
     * 原文中的空白在比较时去掉，但空白处仍视为边界。</p>
     *
     * @param raw    用户原文；可空
     * @param needle 已归一化的值
     * @return 有满足边界条件的命中
     */
    static boolean containsValue(@Nullable String raw, String needle) {
        if (Strings.isBlank(raw) || needle.isEmpty()) {
            return false;
        }

        String str = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder text = new StringBuilder(str.length());
        BitSet gapBefore = new BitSet();
        boolean gap = false;
        for (int i = 0, j = str.length(); i < j; i++) {
            char c = str.charAt(i);
            if (Character.isWhitespace(c)) {
                gap = true;
                continue;
            }

            if (gap) {
                gapBefore.set(text.length());
                gap = false;
            }
            text.append(c);
        }

        boolean boundedStart = isAsciiWord(needle.charAt(0));
        boolean boundedEnd = isAsciiWord(needle.charAt(needle.length() - 1));
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            int end = at + needle.length();
            boolean startOk = !boundedStart || at == 0 || gapBefore.get(at) || !isAsciiWord(text.charAt(at - 1));
            boolean endOk = !boundedEnd || end == text.length() || gapBefore.get(end) || !isAsciiWord(text.charAt(end));
            if (startOk && endOk) {
                return true;
            }
        }

        return false;
    }

    /**
     * 是否英文字母或数字（只有这类字符之间才有词边界的概念）。
     *
     * @param c 字符
     * @return 是 ASCII 字母或数字
     */
    private static boolean isAsciiWord(char c) {
        return c < ASCII_LIMIT && Character.isLetterOrDigit(c);
    }
}
