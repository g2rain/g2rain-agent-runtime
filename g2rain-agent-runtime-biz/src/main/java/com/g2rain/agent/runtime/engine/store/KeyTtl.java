package com.g2rain.agent.runtime.engine.store;


/**
 * 任务组 key 与 TTL（毫秒）。哨兵应比同组短 {@code sentinel-gap}。
 *
 * @param key   Redis key
 * @param ttlMs 过期毫秒
 * @author alpha
 * @since 2026/10/3
 */
public record KeyTtl(String key, long ttlMs) {

}
