/**
 * Hydrate 包：规划与执行前为本轮装上 Agent 技能列表。
 *
 * <p>经 Bundle 加载技能契约与工具名，并由 {@code ContractValidator} 校验契约。Prior 与任务记忆开局写入发生在 Lifecycle 的 {@code turn.start}，
 * 本包不写下游 plan/execute。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.engine.flow.hydrate;

import org.jspecify.annotations.NullMarked;
