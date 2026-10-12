package com.g2rain.agent.runtime.support.trace;

import com.g2rain.agent.runtime.support.RuntimeUtils;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 一条轨迹事件：链式追加 {@code key=value}，最后按级别输出一行。
 *
 * <p>级别语义：{@link #debug()} 只在排查时需要的细节；{@link #info()} 链路的正常里程碑；
 * {@link #warn()} 异常结论，本轮仍可继续或正常收口；{@link #error()} 导致本轮失败的结论。
 * 轨迹不带异常堆栈，堆栈由出错组件自己的日志输出。</p>
 *
 * <p>字段值格式：枚举取 {@code name()}；集合输出 {@code [a,b]}；{@code null} 输出 {@code -}；
 * 单个值超过 {@value #MAX_VALUE_CHARS} 字符截断并以 {@code …} 结尾；含空白或 {@code =} 的值加双引号。
 * 环节关闭时使用空操作实例，所有调用直接返回。</p>
 *
 * <pre>{@code
 * Trace.at(TraceStage.TOOL, "invoked")
 *     .kv("tool", toolName)
 *     .kv("outcome", observation.outcome())
 *     .kv("durationMs", durationMs)
 *     .info();
 * // [Trace][TOOL] invoked tool=create_invoice outcome=SUCCESS durationMs=233
 * }</pre>
 *
 * @author alpha
 * @since 2026/10/11
 */
public final class TraceEvent {

    /**
     * 环节关闭时使用的空操作实例。
     */
    static final TraceEvent NOOP = new TraceEvent();

    /**
     * 单个字段值的最大字符数。
     */
    static final int MAX_VALUE_CHARS = 200;

    /**
     * 正在拼装的一行；空操作实例为 {@code null}。
     */
    private final @Nullable StringBuilder line;

    /**
     * 构造空操作实例。
     */
    private TraceEvent() {
        this.line = null;
    }

    /**
     * 以环节与事件名开始一行。
     *
     * @param stage 链路环节
     * @param event 事件名
     */
    TraceEvent(TraceStage stage, String event) {
        this.line = new StringBuilder(128).append("[Trace][").append(stage.name()).append("] ").append(event);
    }

    /**
     * 追加一个字段。
     *
     * @param key   字段名：小驼峰
     * @param value 字段值；可空
     * @return this
     */
    public TraceEvent kv(String key, @Nullable Object value) {
        if (Objects.nonNull(line)) {
            line.append(' ').append(key).append('=').append(format(value));
        }

        return this;
    }

    /**
     * 追加原文字段 {@code body}：不加引号，换行转义为 {@code \n}，超过上限截断。必须是最后一个字段。
     *
     * <p>仅用于模型完整内容等需要保留原文结构的场景。</p>
     *
     * @param text     原文；可空
     * @param maxChars 字符数上限
     * @return this
     */
    public TraceEvent body(@Nullable String text, int maxChars) {
        if (Objects.nonNull(line)) {
            String value = Objects.isNull(text) ? "-" : text.replace("\r", "").replace("\n", "\\n");
            line.append(" body=").append(RuntimeUtils.abbreviate(value, maxChars));
        }

        return this;
    }

    /**
     * 以 DEBUG 输出：只在排查时需要的细节。
     */
    public void debug() {
        if (Objects.nonNull(line)) {
            Trace.debug(line.toString());
        }
    }

    /**
     * 以 INFO 输出：链路的正常里程碑。
     */
    public void info() {
        if (Objects.nonNull(line)) {
            Trace.info(line.toString());
        }
    }

    /**
     * 以 WARN 输出：异常结论，本轮仍可继续或正常收口。
     */
    public void warn() {
        if (Objects.nonNull(line)) {
            Trace.warn(line.toString());
        }
    }

    /**
     * 以 ERROR 输出：导致本轮失败的结论。
     */
    public void error() {
        if (Objects.nonNull(line)) {
            Trace.error(line.toString());
        }
    }

    /**
     * 已拼装的一行；空操作实例为空串。
     *
     * @return 一行文本
     */
    @Override
    public String toString() {
        return Objects.isNull(line) ? "" : line.toString();
    }

    /**
     * 格式化字段值。
     * <pre>{@code
     * format(null);                  // -
     * format(TurnMode.START_FRESH);  // START_FRESH
     * format(List.of("a", "b"));     // [a,b]
     * format("a b");                 // "a b"
     * }</pre>
     *
     * @param value 字段值；可空
     * @return 格式化后的文本
     */
    static String format(@Nullable Object value) {
        if (Objects.isNull(value)) {
            return "-";
        }

        String text = switch (value) {
            case Enum<?> constant -> constant.name();
            case Collection<?> items -> items.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
            default -> String.valueOf(value);
        };

        text = RuntimeUtils.abbreviate(text, MAX_VALUE_CHARS);
        //noinspection DataFlowIssue。
        boolean quoted = text.isEmpty() || text.chars().anyMatch(ch -> Character.isWhitespace(ch) || ch == '=');
        return quoted ? '"' + text.replace("\"", "\\\"") + '"' : text;
    }
}
