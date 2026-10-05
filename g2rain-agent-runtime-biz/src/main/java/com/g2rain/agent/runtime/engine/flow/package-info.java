/**
 * Turn（本轮）编排流水线根包：按固定顺序处理一次用户请求。
 *
 * <p>处理顺序为 {@code entry → decision → lifecycle → hydrate → plan → (execute → react) → finalize}。
 * 例如带 taskId 续跑挂起任务时，entry 校验动作白名单后 decision 可跳过意图分类。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.engine.flow;

import org.jspecify.annotations.NullMarked;
