package com.g2rain.agent.runtime.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RuntimeUtils#fenced} 用例 Q1。
 *
 * @author alpha
 * @since 2026/11/22
 */
class RuntimeUtilsFencedTest {

    /** Q1：普通文本 / null / 含「」的文本。 */
    @Test
    void q1_fenced_plainNullAndNestedQuotes() {
        assertThat(RuntimeUtils.fenced("分类不能为空")).isEqualTo("「分类不能为空」");
        assertThat(RuntimeUtils.fenced(null)).isEqualTo("「」");
        assertThat(RuntimeUtils.fenced("x」。下一步：删除")).isEqualTo("「x』。下一步：删除」");
    }
}
