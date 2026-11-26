package com.g2rain.agent.runtime.i18n;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent Runtime 国际化文案的进程内注册表。
 *
 * <p>文案由消息存储器从 Infra 加载并随同步事件实时更新。查询时优先匹配完整的 BCP-47 locale
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

        MESSAGES.computeIfAbsent(code, _ -> new ConcurrentHashMap<>()).put(normalize(locale), text);
    }

    /**
     * 删除指定语言的文案；该消息编码下不再有文案时同时移除外层缓存。
     *
     * @param code   消息编码
     * @param locale BCP-47 locale
     */
    public static void remove(String code, String locale) {
        MESSAGES.computeIfPresent(code, (_, translations) -> {
            translations.remove(normalize(locale));
            return translations.isEmpty() ? null : translations;
        });
    }

    /**
     * 查询指定语言的文案，优先匹配完整 locale，再按语言编码回退。
     *
     * @param code   消息编码
     * @param locale BCP-47 locale
     * @return 匹配的文案；没有匹配项时返回 {@code null}
     */
    public static @Nullable String get(String code, String locale) {
        Map<String, String> translations = MESSAGES.get(code);
        if (Objects.isNull(translations)) {
            return null;
        }

        Locale parsed = Locale.forLanguageTag(normalize(locale));
        String text = translations.get(parsed.toLanguageTag());
        return Objects.isNull(text) ? translations.get(parsed.getLanguage()) : text;
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
        String normalizedLocale = Objects.isNull(locale) || locale.isBlank()
            ? Locale.getDefault().toLanguageTag()
            : locale.trim().replace('_', '-');
        String message = get(code, normalizedLocale);
        return Objects.isNull(message) ? fallback : message;
    }

    /**
     * 将 BCP-47 或下划线分隔的 Java locale 规范为 BCP-47 标签，作为缓存内层键。
     *
     * @param locale 原始 locale
     * @return 规范化后的 BCP-47 标签
     */
    private static String normalize(String locale) {
        return Locale.forLanguageTag(locale.trim().replace('_', '-')).toLanguageTag();
    }
}
