package com.g2rain.agent.runtime.engine.tools.guard;

import com.g2rain.common.json.JsonCodecFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ArgBindingInterceptor} 数组模板展开测试。
 *
 * @author alpha
 * @since 2026/11/16
 */
class ArgBindingInterceptorTest {

    private static final JsonNode INPUTS = JsonCodecFactory.instance()
        .str2node("{\"items\":[{\"tags\":[\"a\",\"b\",\"c\"]},{\"tags\":[\"d\"]}]}");

    private static final JsonNode EVIDENCE_ROOT = JsonCodecFactory.instance()
        .str2node("{\"order_lines\":[{\"goodsName\":\"餐饮\",\"amount\":113},{\"goodsName\":\"住宿\",\"amount\":300}]}");

    @Test
    void concretePaths_shouldExpandNestedArraysByEachItemLength() {
        List<ArgBindingInterceptor.PathPair> pairs = ArgBindingInterceptor.concretePaths(
            "items[].tags[]", "articles[].tagIds[]", INPUTS);

        assertEquals(List.of(
            new ArgBindingInterceptor.PathPair("items[0].tags[0]", "articles[0].tagIds[0]"),
            new ArgBindingInterceptor.PathPair("items[0].tags[1]", "articles[0].tagIds[1]"),
            new ArgBindingInterceptor.PathPair("items[0].tags[2]", "articles[0].tagIds[2]"),
            new ArgBindingInterceptor.PathPair("items[1].tags[0]", "articles[1].tagIds[0]")
        ), pairs);
    }

    @Test
    void concretePaths_shouldDropPairsWhenTargetHasMoreWildcards() {
        List<ArgBindingInterceptor.PathPair> pairs = ArgBindingInterceptor.concretePaths(
            "items[].tags", "articles[].tagIds[]", INPUTS);

        assertEquals(List.of(), pairs);
    }

    @Test
    void concretePaths_shouldAlignEvidenceRowsByIndex() {
        List<ArgBindingInterceptor.PathPair> pairs = ArgBindingInterceptor.concretePaths(
            "order_lines[].goodsName", "invoiceDetailList[].goodsName", EVIDENCE_ROOT);

        assertEquals(List.of(
            new ArgBindingInterceptor.PathPair("order_lines[0].goodsName", "invoiceDetailList[0].goodsName"),
            new ArgBindingInterceptor.PathPair("order_lines[1].goodsName", "invoiceDetailList[1].goodsName")
        ), pairs);
    }
}
