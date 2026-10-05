/**
 * Adapter（适配）包：把外部 Studio 等远程结果收成 Runtime 易用的本地门面。
 * <p>
 * 负责拆 Result、空数据兜底，避免引擎直接碰 Feign/Result 细节。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.adapter;

import org.jspecify.annotations.NullMarked;
