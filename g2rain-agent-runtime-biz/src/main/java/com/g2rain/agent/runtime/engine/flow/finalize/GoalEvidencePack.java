package com.g2rain.agent.runtime.engine.flow.finalize;

import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GoalGate 用的本轮证据包：目标、节点摘要、证据摘录与交互历史尾。
 *
 * <p>由 {@link GoalEvidenceAssembler} 从 TurnContext 拼装；观察摘录与汇总结论可后续补填。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class GoalEvidencePack {

    /**
     * 目标摘要，优先任务 goalSummary，否则本轮用户原文；可空。
     */
    private @Nullable String goalSummary;

    /**
     * 本轮用户原文；可空。
     */
    private @Nullable String userInput;

    /**
     * 节点摘要列表，按图顺序截断后写入。
     */
    @Setter
    private List<GoalNodeSummary> nodeSummaries = new ArrayList<>();

    /**
     * 多节点汇总结论；任务前情综合项键名为 {@code continuity}。
     */
    @Setter
    private Map<String, Object> joinSynthesis = new LinkedHashMap<>();

    /**
     * 证据短摘；可空，组装器当前可不填。
     */
    private @Nullable String evidenceDigest;

    /**
     * 成功观察短句列表；组装器当前可不填。
     */
    @Setter
    private List<String> observationSnippets = new ArrayList<>();

    /**
     * 交互历史尾若干条，供指代与接续。
     */
    @Setter
    private List<DialogueMessage> dialogueTail = new ArrayList<>();

    /**
     * @return 目标摘要
     */
    public @Nullable String goalSummary() {
        return goalSummary;
    }

    /**
     * @param goalSummary 目标摘要
     */
    public void setGoalSummary(@Nullable String goalSummary) {
        this.goalSummary = goalSummary;
    }

    /**
     * @return 本轮用户输入
     */
    public @Nullable String userInput() {
        return userInput;
    }

    /**
     * @param userInput 本轮用户输入
     */
    public void setUserInput(@Nullable String userInput) {
        this.userInput = userInput;
    }

    /**
     * @return 节点摘要列表（可变视图）
     */
    public List<GoalNodeSummary> nodeSummaries() {
        return nodeSummaries;
    }

    /**
     * @return 汇总结论 map
     */
    public Map<String, Object> joinSynthesis() {
        return joinSynthesis;
    }

    /**
     * @return 证据短摘
     */
    public @Nullable String evidenceDigest() {
        return evidenceDigest;
    }

    /**
     * @param evidenceDigest 证据短摘
     */
    public void setEvidenceDigest(@Nullable String evidenceDigest) {
        this.evidenceDigest = evidenceDigest;
    }

    /**
     * @return 成功观察短句列表
     */
    public List<String> observationSnippets() {
        return observationSnippets;
    }

    /**
     * @return 交互历史尾
     */
    public List<DialogueMessage> dialogueTail() {
        return dialogueTail;
    }
}
