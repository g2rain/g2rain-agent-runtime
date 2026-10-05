/**
 * Store（仓储）：热态 Redis 读写契约。
 *
 * <p>{@link com.g2rain.agent.runtime.engine.store.StateStore} 是唯一入口。业务禁止直连 Redis；
 * 经状态门面读写。renew / flush 由 Lua 校验会话栅栏。</p>
 *
 * @author alpha
 * @since 2026/9/9
 */
@NullMarked
package com.g2rain.agent.runtime.engine.store;

import org.jspecify.annotations.NullMarked;
