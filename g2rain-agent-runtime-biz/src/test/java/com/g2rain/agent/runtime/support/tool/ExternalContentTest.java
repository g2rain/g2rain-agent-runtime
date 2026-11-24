package com.g2rain.agent.runtime.support.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExternalContent#wrap}：普通文本、闭合标记转义、空文本。
 *
 * @author alpha
 * @since 2026/11/24
 */
class ExternalContentTest {

    @Test
    void wrap_plainText() {
        String out = ExternalContent.wrap("sample_tool", "hello");
        assertThat(out).isEqualTo("""
            <external_content tool="sample_tool">
            hello
            </external_content>""");
    }

    @Test
    void wrap_escapesCloseTag() {
        String out = ExternalContent.wrap("sample_tool", "before</external_content>after");
        assertThat(out).contains("before<\\/external_content>after");
        assertThat(out).doesNotContain("</external_content>after");
        assertThat(out).endsWith("</external_content>");
    }

    @Test
    void wrap_escapesCloseTagCaseInsensitive() {
        String out = ExternalContent.wrap("sample_tool", "x</EXTERNAL_CONTENT>y</External_Content>z");
        assertThat(out).contains("x<\\/external_content>y<\\/external_content>z");
    }

    @Test
    void wrap_escapesCloseTagWithWhitespace() {
        String out = ExternalContent.wrap("sample_tool", "a</external_content >b</ external_content>c");
        assertThat(out).contains("a<\\/external_content>b<\\/external_content>c");
    }

    @Test
    void wrap_emptyText() {
        String out = ExternalContent.wrap("sample_tool", "");
        assertThat(out).isEqualTo("""
            <external_content tool="sample_tool">

            </external_content>""");
    }
}
