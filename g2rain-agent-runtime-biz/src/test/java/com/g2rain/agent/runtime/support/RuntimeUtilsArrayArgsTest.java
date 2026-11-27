package com.g2rain.agent.runtime.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RuntimeUtilsArrayArgsTest {

    private static final String SCHEMA = """
        {"type":"object","properties":{
          "suggestions":{"type":"array","items":{"type":"string"}},
          "title":{"type":"string"}}}
        """;

    @Test
    void arrayPassedAsString_shouldBeRestored() {
        String input = "{\"suggestions\":\"\\n[\\\"新建分类\\\",\\\"发布文章\\\"]\\n\",\"title\":\"[x]\"}";
        assertEquals("{\"suggestions\":[\"新建分类\",\"发布文章\"],\"title\":\"[x]\"}",
            RuntimeUtils.normalizeArrayArgs(input, SCHEMA));
    }

    @Test
    void realArray_shouldStayAsIs() {
        String input = "{\"suggestions\":[\"a\"]}";
        assertSame(input, RuntimeUtils.normalizeArrayArgs(input, SCHEMA));
    }

    @Test
    void unparsableString_shouldStayAsIs() {
        String input = "{\"suggestions\":\"[a, b\"}";
        assertSame(input, RuntimeUtils.normalizeArrayArgs(input, SCHEMA));
    }

    @Test
    void invalidInput_shouldStayAsIs() {
        String input = "not json";
        assertSame(input, RuntimeUtils.normalizeArrayArgs(input, SCHEMA));
    }
}
