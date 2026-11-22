package com.g2rain.agent.runtime.support;

import com.g2rain.common.web.PrincipalContext;
import com.g2rain.common.web.PrincipalContextHolder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link RuntimeUtils#callKey} / {@link RuntimeUtils#requireTraceId} 用例 T3、T10。
 *
 * @author alpha
 * @since 2026/11/22
 */
class RuntimeUtilsCallKeyTest {

    /**
     * 改动前 liveKey 回落：sha256("cms_article_save|" + argsHash({}))。
     */
    private static final String EMPTY_ARGS_CALL_KEY =
        "89fd4f98d0eb8514ac4d74b4577bb36b8c0428c40ea35c82f59d534be930473b";

    /** T3：callKey 与改动前回落算法一致。 */
    @Test
    void t3_callKey_matchesLegacyFallback() {
        assertEquals(EMPTY_ARGS_CALL_KEY,
            RuntimeUtils.callKey("cms_article_save", JsonNodeFactory.instance.objectNode()));
    }

    /** T10：无 traceId 时连续调用两次得到同一值，并写回上下文。 */
    @Test
    void t10_requireTraceId_writesBackAndStable() {
        PrincipalContext context = PrincipalContext.of();
        PrincipalContextHolder.runWith(context, () -> {
            String first = RuntimeUtils.requireTraceId();
            String second = RuntimeUtils.requireTraceId();
            assertNotNull(first);
            assertEquals(first, second);
            assertEquals(first, PrincipalContextHolder.getTraceId());
        });
    }
}
