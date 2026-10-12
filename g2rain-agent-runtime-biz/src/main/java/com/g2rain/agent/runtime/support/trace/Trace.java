package com.g2rain.agent.runtime.support.trace;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.Objects;

/**
 * 链路轨迹的唯一出口。
 *
 * <p>按 {@link TraceStage} 开关；环节关闭时 {@link #at} 返回空操作，后续调用没有开销。
 * 配置由启动配置以对象引用绑定，配置中心刷新后实时生效；未绑定时（如单元测试）使用默认配置。</p>
 *
 * <pre>{@code
 * Trace.at(TraceStage.DECISION, "settled")
 *     .kv("scene", scene)
 *     .kv("settled", mode)
 *     .info();
 * // [Trace][DECISION] settled scene=FRESH settled=START_FRESH
 * }</pre>
 *
 * @author alpha
 * @since 2026/10/11
 */
@Slf4j(topic = "g2rain.agent.trace")
public final class Trace {

    /**
     * 当前生效的轨迹配置。
     */
    private static volatile RuntimeProperties.Tracing settings = new RuntimeProperties.Tracing();

    /**
     * 请求级调试标记的 MDC 键；值为 {@code TRUE} 时，本请求的模型调用输出完整内容。
     */
    public static final String DEBUG_KEY = "DEBUG_PRINT_LEVEL";

    /**
     * 静态入口；禁止实例化。
     */
    private Trace() {

    }

    /**
     * 绑定轨迹配置；启动时调用一次。
     *
     * @param tracing 轨迹配置
     */
    public static void bind(RuntimeProperties.Tracing tracing) {
        settings = Objects.requireNonNull(tracing);
    }

    /**
     * 判断环节是否输出轨迹；构造代价较高的字段值前可先调用。
     *
     * <p>只看总开关与环节开关；日志级别在输出时由对应级别判断。</p>
     *
     * @param stage 链路环节
     * @return 总开关与环节开关均放行时返回 {@code true}
     */
    public static boolean enabled(TraceStage stage) {
        RuntimeProperties.Tracing current = settings;
        return current.isEnabled() && current.getStages().contains(stage);
    }

    /**
     * 开始一条轨迹事件。
     *
     * @param stage 链路环节
     * @param event 事件名：小写英文，多个单词用下划线
     * @return 轨迹事件；环节关闭时为空操作
     */
    public static TraceEvent at(TraceStage stage, String event) {
        return enabled(stage) ? new TraceEvent(stage, event) : TraceEvent.NOOP;
    }

    /**
     * 本次请求生效的模型内容输出策略；请求带调试标记时为 {@code ALL}。
     *
     * @return 模型内容输出策略
     */
    public static RuntimeProperties.Tracing.Payload modelPayload() {
        if (Boolean.TRUE.toString().equalsIgnoreCase(MDC.get(DEBUG_KEY))) {
            return RuntimeProperties.Tracing.Payload.ALL;
        }

        return settings.getModel().getPayload();
    }

    /**
     * 模型完整内容中请求、响应各自的截断上限。
     *
     * @return 字符数上限
     */
    public static int modelMaxChars() {
        return settings.getModel().getMaxChars();
    }

    /**
     * 以 DEBUG 输出一行轨迹；仅供 {@link TraceEvent} 调用。
     *
     * @param line 已拼装的一行
     */
    static void debug(String line) {
        log.debug(line);
    }

    /**
     * 以 INFO 输出一行轨迹；仅供 {@link TraceEvent} 调用。
     *
     * @param line 已拼装的一行
     */
    static void info(String line) {
        log.info(line);
    }

    /**
     * 以 WARN 输出一行轨迹；仅供 {@link TraceEvent} 调用。
     *
     * @param line 已拼装的一行
     */
    static void warn(String line) {
        log.warn(line);
    }

    /**
     * 以 ERROR 输出一行轨迹；仅供 {@link TraceEvent} 调用。
     *
     * @param line 已拼装的一行
     */
    static void error(String line) {
        log.error(line);
    }
}
