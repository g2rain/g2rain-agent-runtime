package com.g2rain.agent.runtime.i18n;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent Runtime 国际化文案的进程内注册表。
 *
 * <p>文案在应用启动时从 Infra 加载。查询时优先匹配完整的 BCP-47 locale
 * （例如 {@code en-US}），再按语言编码匹配（例如 {@code en}）；都未命中时返回调用方提供的枚举兜底文案。</p>
 *
 * <p>注册表由进程内所有请求共享，并发安全，支持 SSE 请求在多个虚拟线程中同时查询。</p>
 *
 * @author alpha
 * @since 2026/10/31
 */
public final class RuntimeMessageRegistry {

    /**
     * 国际化文案缓存；外层键为消息编码，内层键为规范化后的 locale。
     */
    private static final Map<String, Map<String, String>> MESSAGES = new ConcurrentHashMap<>();

    /**
     * 禁止实例化；注册表通过静态方法在进程内共享。
     */
    private RuntimeMessageRegistry() {

    }

    /**
     * 注册或覆盖一条指定语言的文案。
     *
     * @param code   {@code RuntimeMessage} 中定义的消息编码，为 {@code null} 时忽略本次注册
     * @param locale BCP-47 locale 或下划线分隔的 Java locale，为 {@code null} 时忽略本次注册
     * @param text   当前语言的文案模板，可包含具名占位符；为 {@code null} 时忽略本次注册
     */
    public static void put(@Nullable String code, @Nullable String locale, @Nullable String text) {
        if (Objects.isNull(code) || Objects.isNull(locale) || Objects.isNull(text)) {
            return;
        }

        MESSAGES.computeIfAbsent(code, ignored -> new ConcurrentHashMap<>()).put(normalize(locale), text);
    }

    /**
     * 根据 locale 查询文案；没有匹配项时返回枚举默认文案。
     *
     * @param code     消息编码
     * @param locale   请求的 BCP-47 locale；为 {@code null} 时按 JVM 默认 locale 查询
     * @param fallback 没有匹配文案时返回的默认文本
     * @return 匹配的文案模板，或 {@code fallback}
     */
    public static String resolve(String code, @Nullable String locale, String fallback) {
        Map<String, String> translations = MESSAGES.get(code);
        if (Objects.isNull(translations)) {
            return fallback;
        }

        Locale parsed = Objects.isNull(locale) || locale.isBlank()
            ? Locale.getDefault()
            : Locale.forLanguageTag(locale.trim().replace('_', '-'));
        String full = parsed.toLanguageTag();
        String text = translations.get(full);
        if (Objects.isNull(text)) {
            text = translations.get(parsed.getLanguage());
        }

        return Objects.isNull(text) ? fallback : text;
    }

    /**
     * 将 locale 统一为 BCP-47 格式，供缓存键使用。
     *
     * @param locale 待规范化的 locale 字符串
     * @return 规范化后的 BCP-47 locale
     */
    private static String normalize(String locale) {
        return Locale.forLanguageTag(locale.trim().replace('_', '-')).toLanguageTag();
    }
}
