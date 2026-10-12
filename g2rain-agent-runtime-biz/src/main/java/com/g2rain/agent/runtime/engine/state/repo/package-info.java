/**
 * 运行时状态的冷存储仓储：按表聚合读写会话、任务、图、工具调用与报文。
 * <p>
 * 只被 {@code engine.state} 内的门面、回源与证据流调用；轮末落库由
 * {@link com.g2rain.agent.runtime.engine.state.RuntimeStatePersistence} 在一个事务内编排。
 * </p>
 *
 * @author alpha
 * @since 2026/10/12
 */
@NullMarked
package com.g2rain.agent.runtime.engine.state.repo;

import org.jspecify.annotations.NullMarked;
