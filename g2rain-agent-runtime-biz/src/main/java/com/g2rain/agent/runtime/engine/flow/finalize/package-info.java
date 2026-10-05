/**
 * Finalize（本轮收口）包：评估任务结果、组织答复、写入交互历史并关闭终态 Task。
 *
 * <p>{@link com.g2rain.agent.runtime.engine.flow.finalize.Finalizer} 是入口；
 * {@link com.g2rain.agent.runtime.engine.flow.finalize.GoalGate} 把确定性草稿与证据收成给人看的答复。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.engine.flow.finalize;

import org.jspecify.annotations.NullMarked;
