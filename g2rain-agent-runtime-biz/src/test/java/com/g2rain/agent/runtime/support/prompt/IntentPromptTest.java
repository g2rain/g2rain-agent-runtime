package com.g2rain.agent.runtime.support.prompt;

import com.g2rain.agent.runtime.domain.decision.TurnMode;
import com.g2rain.agent.runtime.domain.decision.TurnScene;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图提示词模板：占位符、结尾输出指令与场景规则覆盖。
 *
 * @author alpha
 * @since 2026/10/10
 */
class IntentPromptTest {

    @Test
    void userTemplateHasExactlyThreePlaceholders() {
        assertThat(IntentPrompt.USER.split("%s", -1)).hasSize(4);
    }

    @Test
    void userPromptEndsWithOutputInstruction() {
        String user = IntentPrompt.USER.formatted(TurnScene.FRESH.name(), "{\"agentLocked\":false}", "帮我开张发票");

        assertThat(user).contains("【当前场景】\nFRESH", "【本轮用户输入】\n帮我开张发票");
        assertThat(user.strip()).startsWith("请根据以下规则").endsWith("\"userReply\":<文本或 null>}")
            .contains("{\"mode\":\"<推进规则中箭头右侧的值>\"");
    }

    @Test
    void everySceneModeIsDescribed() {
        for (TurnScene scene : TurnScene.values()) {
            assertThat(IntentPrompt.USER).contains(scene.name() + "（");
            for (TurnMode mode : scene.modes(false)) {
                assertThat(IntentPrompt.USER).contains("→ " + mode.name());
            }
        }
        assertThat(IntentPrompt.USER).doesNotContain(TurnMode.UNCERTAIN.name());
    }
}
