package com.g2rain.agent.runtime.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeUtilsUnescapeTest {

    @Test
    void literalNewlines_shouldBeRestored() {
        assertEquals("第一段\n\n参考：v2", RuntimeUtils.unescapeLiteral("第一段\\n\\n参考：v2"));
        assertEquals("a\nb\tc", RuntimeUtils.unescapeLiteral("a\\r\\nb\\tc"));
    }

    @Test
    void textWithRealNewline_shouldStayAsIs() {
        String code = "示例：\n```java\nSystem.out.println(\"\\n\");\n```";
        assertEquals(code, RuntimeUtils.unescapeLiteral(code));
    }

    @Test
    void textWithoutLiteralNewline_shouldStayAsIs() {
        assertEquals("单行回答", RuntimeUtils.unescapeLiteral("单行回答"));
    }
}
