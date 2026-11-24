package com.g2rain.agent.runtime.support.tool;

import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.guard.EvidenceReader;
import com.g2rain.agent.runtime.i18n.RuntimeMessageRegistry;
import com.g2rain.common.web.PrincipalContext;
import com.g2rain.common.web.PrincipalContextHolder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link ToolCapabilityContext#text}：按语言取值、fallback、具名替换与奇数参数。
 *
 * @author alpha
 * @since 2026/11/24
 */
class ToolCapabilityContextTest {

    private static final String CODE = "capability.builtin_sample_lookup.note";

    @Test
    void text_usesRegistryForLocale() {
        RuntimeMessageRegistry.put(CODE, "en-US", "Note for {name}");
        ToolCapabilityContext context = context();

        withLocale("en-US", () -> assertThat(context.text(CODE, "说明：{name}", "name", "Ada"))
            .isEqualTo("Note for Ada"));
    }

    @Test
    void text_fallsBackWhenMissing() {
        ToolCapabilityContext context = context();

        withLocale("en-US", () -> assertThat(
            context.text("capability.builtin_sample_lookup.missing", "缺省：{name}", "name", "Ada"))
            .isEqualTo("缺省：Ada"));
    }

    @Test
    void text_replacesNamedPlaceholders() {
        RuntimeMessageRegistry.put(CODE, "zh-CN", "共 {count} 条，来自 {source}");
        ToolCapabilityContext context = context();

        withLocale("zh-CN", () -> assertThat(
            context.text(CODE, "fallback", "count", "3", "source", "示例"))
            .isEqualTo("共 3 条，来自 示例"));
    }

    @Test
    void text_ignoresTrailingOddArgument() {
        RuntimeMessageRegistry.put(CODE, "en-US", "Hello {name}");
        ToolCapabilityContext context = context();

        withLocale("en-US", () -> assertThat(context.text(CODE, "你好 {name}", "name", "Ada", "orphan"))
            .isEqualTo("Hello Ada"));
    }

    private static ToolCapabilityContext context() {
        return new ToolCapabilityContext(mock(ToolExecutionContext.class), mock(EvidenceReader.class),
            mock(RuntimeStateFacade.class));
    }

    private static void withLocale(String locale, Runnable action) {
        PrincipalContext principal = PrincipalContext.of();
        principal.setAcceptLanguage(locale);
        PrincipalContextHolder.runWith(principal, action);
    }
}
