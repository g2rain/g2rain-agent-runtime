package com.g2rain.agent.runtime.support.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 技能筛选、任务规划、答复整理三份提示词模板：只有一个数据占位符，填入后数据位于输出指令之前。
 *
 * @author alpha
 * @since 2026/10/11
 */
class PromptTemplateTest {

    private static final List<String> TEMPLATES = List.of(
        SkillCandidatePrompt.USER, PlanPrompt.USER, GoalGatePrompt.USER
    );

    @Test
    void eachTemplateHasExactlyOnePlaceholder() {
        TEMPLATES.forEach(template -> assertThat(template.split("%s", -1)).hasSize(2));
    }

    @Test
    void dataComesBeforeOutputInstruction() {
        String data = "{\"input\":\"查一下网站\"}";
        TEMPLATES.forEach(template -> {
            String user = template.formatted(data);
            assertThat(user.indexOf(data)).isLessThan(user.indexOf("【输出】"));
            assertThat(user.strip()).endsWith("}");
        });
    }
}
