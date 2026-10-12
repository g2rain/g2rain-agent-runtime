package com.g2rain.agent.runtime.engine.flow.decision;


import com.g2rain.agent.runtime.domain.decision.IntentResult;
import org.jspecify.annotations.Nullable;

/**
 * IntentClassifier（意图分类器）：在本轮场景内，根据任务前情、交互历史与候选 Agent 判断推进模式与人选。
 *
 * <p>返回规范化后的 {@link IntentResult}。分类失败或模型无有效输出时返回 {@code null}，由调度器按场景兜底。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface IntentClassifier {

    /**
     * 对本轮意图做分类。
     *
     * @param request 分类输入（场景、是否指定员工、前情摘要、候选 Agent、用户原文等）
     * @return 意图结果；失败或无有效输出为 {@code null}
     */
    @Nullable
    IntentResult classify(IntentRequest request);
}
