package com.g2rain.agent.runtime.engine.tools.guard;

import com.g2rain.agent.runtime.support.RuntimeUtils;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link IdempotencyKeyBuilder} 用例 T4。
 *
 * @author alpha
 * @since 2026/11/22
 */
class IdempotencyKeyBuilderTest {

    /** T4：契约没有幂等键 → 等于 callKey。 */
    @Test
    void t4_effectiveKey_withoutContract_equalsCallKey() {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("title", "示例");

        assertEquals(RuntimeUtils.callKey("cms_article_save", args),
            IdempotencyKeyBuilder.effectiveKey("cms_article_save", null, args));
    }
}
