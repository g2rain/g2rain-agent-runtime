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

    /** PR-6：WRITE_PENDING 下“要求修改”在“同意执行”之前，且为 REPLY_ONLY；userReply 有修改说明。 */
    @Test
    void writePending_modifyBeforeContinue_replyOnly() {
        String user = IntentPrompt.USER;
        int writePending = user.indexOf("WRITE_PENDING（");
        int modify = user.indexOf("要求修改待确认操作的内容（如改参数、换内容）→ REPLY_ONLY", writePending);
        int continueTask = user.indexOf("同意执行，或回答确认问题 → CONTINUE_TASK", writePending);
        assertThat(modify).isGreaterThan(writePending);
        assertThat(continueTask).isGreaterThan(modify);
        assertThat(user).contains(
            "WRITE_PENDING 下要求修改待确认的操作：说明待确认的操作不能直接修改；如需修改，请先拒绝（拒绝后当前事项结束），再重新描述需求。");
    }
}
