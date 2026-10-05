package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.hydrate.ValueRef;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.text.Normalizer;
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

    private static final JsonCodec JSON = JsonCodecFactory.instance();

    private final EvidenceReader evidenceReader;

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
        if (Strings.isBlank(needle) || needle.length() < 2) {
            return Optional.empty();
        }

        for (var message : ctx.getFrame().turn().dialogue(properties.getState().getDialogueMax())) {
            if (message.getRole() != DialogueRole.USER) {
                continue;
            }

            String text = message.getText();
            if (Strings.isBlank(text) || !normalizeText(text).contains(needle)) {
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
     * FOLLOW_UP 检查点的 expectedFields 是否已直接认定该参数路径。
     *
     * @param ctx       当前工具执行上下文
     * @param paramPath 参数路径
     * @return 当前检查点为追问且字段列表含该路径或叶子键
     */
    public boolean followUpAccepted(ToolExecutionContext ctx, String paramPath) {
        Optional<TaskCheckpoint> checkpoint = ctx.getFrame().turn().checkpoint();
        if (checkpoint.isEmpty() || checkpoint.get().getType() != CheckpointType.FOLLOW_UP) {
            return false;
        }

        var fields = checkpoint.get().getExpectedFields();
        if (fields.isEmpty()) {
            return false;
        }

        String leaf = leafKey(paramPath);
        return fields.contains(paramPath) || fields.contains(leaf);
    }

    /**
     * 入参值是否与某条成功证据精确相等（无子串匹配）。
     *
     * @param ctx    当前工具执行上下文
     * @param argVal 待比对的入参值
     * @return 证据 extract 子树中存在相等节点
     */
    public boolean matchesEvidenceValue(ToolExecutionContext ctx, JsonNode argVal) {
        return evidenceReader.matchesExact(ctx, argVal);
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
        if (Strings.isBlank(needle)) {
            return false;
        }

        return ctx.getFrame().turn().dialogue(properties.getState().getDialogueMax()).stream()
            .filter(message -> message.getRole() == DialogueRole.USER)
            .filter(message -> Objects.equals(messageId, message.getMessageId()))
            .anyMatch(message -> Strings.isNotBlank(message.getText())
                && normalizeText(message.getText()).contains(needle));
    }

    /**
     * 值是否与共享/会话/任务前情袋中带源值相等。
     *
     * @param bag      带源 Map
     * @param source   声明来源
     * @param expected 入参值
     * @return 袋中有相等值
     */
    private boolean matchesBagged(Map<String, Sourced<?>> bag, Source source, JsonNode expected) {
        if (bag.isEmpty()) {
            return false;
        }

        String key = Strings.defaultIfBlank(source.path(), source.ref());
        if (Strings.isNotBlank(key) && bag.containsKey(key)) {
            Sourced<?> sourced = bag.get(key);
            return Objects.nonNull(sourced)
                && JSON.nodesEqual(JSON.obj2node(sourced.value()), expected)
                && (Strings.isBlank(source.ref())
                || Objects.equals(source.ref(), sourced.source().ref())
                || Objects.equals(source.ref(), key));
        }

        return bag.values().stream()
            .filter(sourced -> sourced.source().type() == source.type())
            .filter(sourced -> Strings.isBlank(source.ref()) || Objects.equals(source.ref(), sourced.source().ref()))
            .anyMatch(sourced -> JSON.nodesEqual(JSON.obj2node(sourced.value()), expected));
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

        return ctx.getFrame().turn().platform().matches(leafKey(key), expected);
    }

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
     * 取路径最后一段。
     *
     * @param path 字段路径
     * @return 叶子键；空白路径为空串
     */
    private static String leafKey(String path) {
        if (Strings.isBlank(path)) {
            return "";
        }

        int dot = path.lastIndexOf('.');
        return dot < 0 ? path.trim() : path.substring(dot + 1).trim();
    }
}
