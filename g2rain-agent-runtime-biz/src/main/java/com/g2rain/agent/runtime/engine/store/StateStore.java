package com.g2rain.agent.runtime.engine.store;


import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 热态按 key 读写 Hash / List / String，并执行 renew / flush Lua。
 *
 * @author alpha
 * @since 2026/10/3
 */
public interface StateStore {

    /**
     * 推进会话栅栏并续期会话 / 任务组 key。
     *
     * @param stateKey         会话 state Hash
     * @param traceId          本轮 traceId
     * @param conversationTtl  会话 TTL
     * @param sessionSharedKey 会话 shared key；空则不续期
     * @param extraKeys        任务组 key 与各自 TTL
     * @param baseVersion      Redis 无 version 时的基线（MySQL {@code state_version}）
     * @return 新 version 与写入前的 lastTraceId
     */
    RenewResult renew(String stateKey, String traceId, Duration conversationTtl,
                      @Nullable String sessionSharedKey, List<KeyTtl> extraKeys, long baseVersion);

    /**
     * 按本轮栅栏原子写入改动列表。
     *
     * @param stateKey     会话 state Hash
     * @param fenceVersion TurnScope 持有的 version
     * @param ops          改动列表
     * @return {@code true} 写入成功；{@code false} 栅栏不符
     */
    boolean flush(String stateKey, long fenceVersion, List<FlushOp> ops);

    /**
     * 读 String 值。
     *
     * @param key Redis key
     * @return 值；不存在为 {@code null}
     */
    @Nullable String get(String key);

    /**
     * 读整个 Hash。
     *
     * @param key Redis key
     * @return field → value；键不存在为空 Map
     */
    Map<String, String> hGetAll(String key);

    /**
     * 读 Hash 单个 field。
     *
     * @param key   Redis key
     * @param field Hash 字段
     * @return 值；不存在为 {@code null}
     */
    @Nullable String hGet(String key, String field);

    /**
     * 读 List 区间。
     *
     * @param key   Redis key
     * @param start 起始下标
     * @param end   结束下标（含）
     * @return 元素；键不存在为空 List
     */
    List<String> lRange(String key, long start, long end);

    /**
     * 判断 key 是否存在。
     *
     * @param key Redis key
     * @return 存在为 {@code true}
     */
    boolean exists(String key);

    /**
     * 删除若干 key。
     *
     * @param keys 待删 Redis key
     */
    void del(String... keys);
}
