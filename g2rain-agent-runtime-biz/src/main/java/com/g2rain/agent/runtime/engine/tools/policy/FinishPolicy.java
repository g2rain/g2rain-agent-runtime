package com.g2rain.agent.runtime.engine.tools.policy;


/**
 * 节点 finish 门槛。
 *
 * <p>通过则允许落产出并结束节点；拒绝则返回原因，节点可继续循环。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface FinishPolicy {

    /**
     * 评估是否允许 finish。
     *
     * @param context 评估入参
     * @return 允许或拒绝及原因
     */
    FinishDecision evaluate(FinishContext context);
}
