package com.g2rain.agent.runtime.engine.tools.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SourceVerifier#containsValue} 边界判断测试。
 *
 * @author alpha
 * @since 2026/11/16
 */
class SourceVerifierContainsTest {

    @Test
    void containsValue_shouldRejectDigitsInsideLongerNumber() {
        assertFalse(SourceVerifier.containsValue("这是 2012 年的数据", "12"));
        assertFalse(SourceVerifier.containsValue("编号abc12", "12"));
    }

    @Test
    void containsValue_shouldAcceptValueSeparatedBySpaceOrPunctuation() {
        assertTrue(SourceVerifier.containsValue("编号 12 和 2012", "12"));
        assertTrue(SourceVerifier.containsValue("查一下订单 ORD-10086 的物流", "ord-10086"));
        assertTrue(SourceVerifier.containsValue("订单号：12，谢谢", "12"));
    }

    @Test
    void containsValue_shouldTreatChineseAsPlainContains() {
        assertTrue(SourceVerifier.containsValue("发布到官网吧", "官网"));
        assertTrue(SourceVerifier.containsValue("把第12篇文章发布", "12"));
    }
}
