/**
 * 链路轨迹日志：一轮对话经过的环节、各环节做出的决定、每次大模型调用的结果。
 *
 * <p>{@link com.g2rain.agent.runtime.support.trace.Trace} 是唯一出口，按
 * {@link com.g2rain.agent.runtime.support.trace.TraceStage} 开关，固定使用 logger {@code g2rain.agent.trace}，
 * 每条事件输出一行 {@code [Trace][环节] 事件 key=value ...}。
 * {@link com.g2rain.agent.runtime.support.trace.TraceScope} 把会话、任务、节点 ID 写入 MDC，
 * 使本轮所有日志自动带上定位信息。</p>
 *
 * @author alpha
 * @since 2026/10/11
 */
@NullMarked
package com.g2rain.agent.runtime.support.trace;

import org.jspecify.annotations.NullMarked;
