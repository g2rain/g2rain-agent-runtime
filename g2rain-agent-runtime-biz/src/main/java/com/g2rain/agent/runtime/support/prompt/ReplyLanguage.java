package com.g2rain.agent.runtime.support.prompt;


import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContextHolder;

import java.util.Locale;

/**
 * 回复语言标签：取自当前请求 {@code acceptLanguage}（过滤器已归一为 BCP-47）。
 *
 * <p>无 Context 或未写入时回落 JVM 默认 Locale，与
 * {@code PrincipalContextScopeFilter#resolveAcceptLanguage} 缺省口径一致。</p>
 *
 * @author alpha
 * @since 2026/10/31
 */
public final class ReplyLanguage {

    /**
     * 当前请求的回复语言 BCP-47 标签（如 {@code ja}、{@code en-US}）。
     *
     * @return 非空语言标签
     */
    public static String tag() {
        String lang = PrincipalContextHolder.getAcceptLanguage();
        return Strings.isBlank(lang) ? Locale.getDefault().toLanguageTag() : lang;
    }

    /**
     * 常量持有者；禁止实例化。
     */
    private ReplyLanguage() {

    }
}
