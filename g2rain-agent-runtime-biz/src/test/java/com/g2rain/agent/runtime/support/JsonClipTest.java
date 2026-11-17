package com.g2rain.agent.runtime.support;

import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonClip} 保结构裁剪测试。
 *
 * @author alpha
 * @since 2026/11/16
 */
class JsonClipTest {

    private static final JsonCodec JSON = JsonCodecFactory.instance();

    private static final int MAX = 600;

    private static final int ARTICLES = 5;

    @Test
    void of_shouldReturnOriginalWhenWithinLimit() {
        JsonNode node = JSON.str2node("{\"id\":\"1\",\"title\":\"G2rain\"}");

        assertEquals(JSON.obj2str(node), JsonClip.of(node, MAX));
    }

    @Test
    void of_shouldShortenLongTextAndKeepShortFields() {
        ArrayNode list = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < ARTICLES; i++) {
            ObjectNode article = list.addObject();
            article.put("id", "a" + i);
            article.put("title", "文章" + i);
            article.put("body", "正文".repeat(500));
        }

        String clipped = JsonClip.of(list, MAX);

        assertTrue(clipped.length() <= MAX);
        JsonNode parsed = JSON.str2node(clipped);
        assertEquals(ARTICLES, parsed.size());
        for (int i = 0; i < ARTICLES; i++) {
            assertEquals("a" + i, parsed.get(i).get("id").asString());
            assertEquals("文章" + i, parsed.get(i).get("title").asString());
        }
    }
}
