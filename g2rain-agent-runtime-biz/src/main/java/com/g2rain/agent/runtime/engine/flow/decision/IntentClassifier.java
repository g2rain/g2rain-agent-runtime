package com.g2rain.agent.runtime.engine.flow.decision;


import com.g2rain.agent.runtime.domain.decision.IntentResult;
import org.jspecify.annotations.Nullable;

/**
 * IntentClassifier（意图分类器）：根据任务前情、交互历史与候选 Agent 判断推进模式与人选倾向。
 *
 * <p>返回 {@link IntentResult}。分类失败或模型空响应时可返回 {@code null}，由调度器降级为 UNCERTAIN。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface IntentClassifier {

    /**
     * 对本轮意图做分类。
     *
     * @param request 分类输入（运行态布尔、摘抄、候选 Agent、用户原文等）
     * @return 意图结果；失败或空响应可为 {@code null}
     */
    @Nullable
    IntentResult classify(IntentRequest request);
}
