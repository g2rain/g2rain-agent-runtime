package com.g2rain.agent.runtime.engine.store;


import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import com.g2rain.data.redis.StringRedisHelper;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Redis 热态实现：按 key 读写，renew / flush 走 Lua 校验栅栏。
 *
 * <p>业务禁止直连本类；经 Facade 调用 {@link StateStore}。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Component
public class RedisStateStore implements StateStore {

    /**
     * JSON 编解码：flush 改动列表序列化给 Lua。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * Redis 字符串助手（含 {@code execute} 跑 Lua）。
     */
    private final StringRedisHelper redis;

    /**
     * renew.lua：推进 version，返回旧 lastTraceId，并续期。
     */
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> renewScript;

    /**
     * flush.lua：栅栏不符返回 0，否则按 ops 写入并返回 1。
     */
    private final RedisScript<Long> flushScript;

    /**
     * 加载 classpath 上的 Lua，绑定返回类型。
     *
     * @param redis Redis 助手
     */
    @SuppressWarnings("rawtypes")
    public RedisStateStore(StringRedisHelper redis) {
        this.redis = redis;
        // 栅栏推进 + 续期
        DefaultRedisScript<List> renew = new DefaultRedisScript<>();
        renew.setLocation(new ClassPathResource("lua/renew.lua"));
        renew.setResultType(List.class);
        this.renewScript = renew;
        // 校验 fence 后原子应用改动
        DefaultRedisScript<Long> flush = new DefaultRedisScript<>();
        flush.setLocation(new ClassPathResource("lua/flush.lua"));
        flush.setResultType(Long.class);
        this.flushScript = flush;
    }

    /**
     * 推进会话栅栏并续期会话 / 任务组 key。
     *
     * @param stateKey         会话 state Hash
     * @param traceId          本轮 traceId，写入 lastTraceId
     * @param conversationTtl  会话 TTL
     * @param sessionSharedKey 会话 shared key；空则不续期
     * @param extraKeys        任务组 key 与各自 TTL（哨兵应更短）
     * @param baseVersion      Redis 无 version 时写入的基线
     * @return 新 version 与写入前的 lastTraceId
     */
    @Override
    public RenewResult renew(String stateKey, String traceId, Duration conversationTtl,
                             @Nullable String sessionSharedKey, List<KeyTtl> extraKeys, long baseVersion) {
        // ARGV：traceId、会话 TTL、session shared、baseVersion、任务 key/ttl 成对
        List<String> args = new ArrayList<>();
        args.add(traceId);
        args.add(Long.toString(conversationTtl.toMillis()));
        args.add(Objects.requireNonNullElse(sessionSharedKey, ""));
        args.add(Long.toString(baseVersion));
        extraKeys.forEach(extra -> {
            args.add(extra.key());
            args.add(Long.toString(extra.ttlMs()));
        });
        List<?> raw = Objects.requireNonNull(redis.execute(renewScript, List.of(stateKey), args.toArray(new Object[0])));
        long version = ((Number) raw.getFirst()).longValue();
        String previous = raw.size() > 1 ? String.valueOf(raw.get(1)) : "";
        return new RenewResult(version, Strings.isBlank(previous) ? null : previous);
    }

    /**
     * 按本轮栅栏原子写入改动列表。
     *
     * @param stateKey     会话 state Hash
     * @param fenceVersion TurnScope 持有的 version
     * @param ops          改动；写工具的 EvidenceHead 与 payload 须在同一次调用
     * @return {@code true} 写入成功；{@code false} 栅栏不符
     */
    @Override
    public boolean flush(String stateKey, long fenceVersion, List<FlushOp> ops) {
        String payload = JSON.obj2str(ops);
        Long ok = redis.execute(flushScript, List.of(stateKey), Long.toString(fenceVersion), payload);
        return Objects.equals(ok, 1L);
    }

    /**
     * 读 String 值。
     *
     * @param key Redis key
     * @return 值；不存在为 {@code null}
     */
    @Override
    public @Nullable String get(String key) {
        return redis.get(key);
    }

    /**
     * 读整个 Hash。
     *
     * @param key Redis key
     * @return field → value；键不存在为空 Map
     */
    @Override
    public Map<String, String> hGetAll(String key) {
        Map<String, String> all = redis.hGetAll(key);
        return Objects.isNull(all) ? Map.of() : all;
    }

    /**
     * 读 Hash 单个 field。
     *
     * @param key   Redis key
     * @param field Hash 字段
     * @return 值；不存在为 {@code null}
     */
    @Override
    public @Nullable String hGet(String key, String field) {
        return redis.hGet(key, field);
    }

    /**
     * 读 List 区间。
     *
     * @param key   Redis key
     * @param start 起始下标
     * @param end   结束下标（含）
     * @return 元素；键不存在为空 List
     */
    @Override
    public List<String> lRange(String key, long start, long end) {
        List<String> range = redis.lRange(key, start, end);
        return Objects.isNull(range) ? List.of() : range;
    }

    /**
     * 判断 key 是否存在。
     *
     * @param key Redis key
     * @return 存在为 {@code true}
     */
    @Override
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redis.exists(key));
    }

    /**
     * 删除若干 key。
     *
     * @param keys 待删 Redis key
     */
    @Override
    public void del(String... keys) {
        Arrays.stream(keys).forEach(redis::delete);
    }
}
