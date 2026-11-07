package com.g2rain.agent.runtime.domain.decision;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场景判定、可选模式与兜底模式。
 *
 * @author alpha
 * @since 2026/10/10
 */
class TurnSceneTest {

    @Test
    void ofResolvesSceneFromRuntimeState() {
        assertThat(TurnScene.of(false, false, false)).isEqualTo(TurnScene.FRESH);
        assertThat(TurnScene.of(false, true, false)).isEqualTo(TurnScene.CONTINUITY);
        assertThat(TurnScene.of(true, false, false)).isEqualTo(TurnScene.ACTIVE_TASK);
        assertThat(TurnScene.of(true, true, false)).isEqualTo(TurnScene.ACTIVE_TASK);
        assertThat(TurnScene.of(true, true, true)).isEqualTo(TurnScene.WRITE_PENDING);
        assertThat(TurnScene.of(false, true, true)).isEqualTo(TurnScene.CONTINUITY);
    }

    @Test
    void modesNeverOfferUncertain() {
        for (TurnScene scene : TurnScene.values()) {
            assertThat(scene.modes(false)).doesNotContain(TurnMode.UNCERTAIN);
            assertThat(scene.modes(false)).contains(TurnMode.REPLY_ONLY);
        }
    }

    @Test
    void lockedAgentCannotSwitch() {
        assertThat(TurnScene.ACTIVE_TASK.modes(false)).contains(TurnMode.SWITCH_AGENT);
        assertThat(TurnScene.ACTIVE_TASK.modes(true)).doesNotContain(TurnMode.SWITCH_AGENT)
            .containsExactly(TurnMode.CANCEL_TASK, TurnMode.REPLACE_TASK, TurnMode.CONTINUE_TASK, TurnMode.REPLY_ONLY);
    }

    @Test
    void writePendingPutsInterruptionsBeforeContinue() {
        assertThat(TurnScene.WRITE_PENDING.modes(true))
            .containsExactly(TurnMode.CANCEL_TASK, TurnMode.REPLACE_TASK, TurnMode.CONTINUE_TASK, TurnMode.REPLY_ONLY);
    }

    @Test
    void fallbackIsAlwaysAnAllowedMode() {
        for (TurnScene scene : TurnScene.values()) {
            assertThat(scene.modes(true)).contains(scene.fallback());
        }
    }
}
