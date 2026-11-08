/**
 * Finish（节点收口）包：判断节点是否允许完成并执行收口。
 * <p>
 * {@link com.g2rain.agent.runtime.engine.tools.finish.FinishPolicy} 供控流在落产出前调用；完成目标取节点 target。
 * {@link com.g2rain.agent.runtime.engine.tools.finish.NodeFinisher} 统一执行收口，供 finish 内置工具与工具契约 returnDirect 共用。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.engine.tools.finish;

import org.jspecify.annotations.NullMarked;
