/**
 * Client（远程客户端）包：对外部服务的 Feign 客户端声明，以及收成本地门面的 {@code AgentProfiles}。
 * <p>
 * 门面负责拆 Result、空数据兜底，避免引擎直接碰 Feign/Result 细节。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.client;

import org.jspecify.annotations.NullMarked;
