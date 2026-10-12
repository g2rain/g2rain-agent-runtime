/**
 * ReAct（节点内循环）包：在单个任务节点内组提示、调模型、执行工具并观察，直到完成、挂起或失败。
 *
 * <p>协作提示组装、上下文投影、执行阶段提示、停滞跟踪。完成门槛策略在 {@code engine.policy}，
 * 本包只消费拒绝原因，不实现门槛本身。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.engine.flow.react;

import org.jspecify.annotations.NullMarked;
