package com.g2rain.agent.runtime.engine.flow.finalize;


import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;

import java.util.List;

/**
 * GoalGate 用的本轮证据包：用户目标、各节点事实与最近对话。
 *
 * @param goal         用户目标：任务目标摘要，没有则用本轮用户原文
 * @param nodes        任务图节点事实，按图顺序
 * @param dialogueTail 最近若干条对话，供指代与语言参照
 * @author alpha
 * @since 2026/8/21
 */
public record GoalEvidencePack(String goal, List<GoalNodeSummary> nodes, List<DialogueMessage> dialogueTail) {

    public GoalEvidencePack {
        nodes = List.copyOf(nodes);
        dialogueTail = List.copyOf(dialogueTail);
    }
}
