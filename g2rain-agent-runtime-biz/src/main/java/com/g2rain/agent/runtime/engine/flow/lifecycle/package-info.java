/**
 * Lifecycle（任务生命周期）包：按本轮决策准备上下文里的 Task。
 *
 * <p>负责续跑前提校验、按需取消上一 Task、按需 {@code turn.start} 开新任务。
 * Prior（任务前情切片）在开任务时写入；技能列表由后续 Hydrator 装载。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.engine.flow.lifecycle;

import org.jspecify.annotations.NullMarked;
