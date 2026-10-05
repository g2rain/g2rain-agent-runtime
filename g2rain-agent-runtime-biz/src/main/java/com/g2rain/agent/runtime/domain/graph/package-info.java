/**
 * 任务执行图（TaskGraph）领域模型。
 * <p>
 * 描述一次 Task 里要跑哪些节点、节点之间的依赖边，以及调度时用到的
 * 节点状态、图帧上下文、本步调度动作（GraphStep）。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.domain.graph;

import org.jspecify.annotations.NullMarked;
