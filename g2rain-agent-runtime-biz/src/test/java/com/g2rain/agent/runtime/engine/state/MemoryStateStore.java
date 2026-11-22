package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.engine.store.FlushOp;
import com.g2rain.agent.runtime.engine.store.KeyTtl;
import com.g2rain.agent.runtime.engine.store.RenewResult;
import com.g2rain.agent.runtime.engine.store.StateStore;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 测试用内存 StateStore：模拟 Hash / String / List、TTL，以及 renew / flush 的栅栏语义。
 *
 * @author alpha
 * @since 2026/11/22
 */
public final class MemoryStateStore implements StateStore {

    /**
     * JSON 编解码：与 RedisStateStore 一样把 lastTraceId 编成 JSON 文本。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * String / Hash / List 共用的内存条目。
     */
    private final Map<String, Entry> entries = new HashMap<>();

    /**
     * 最近一次成功 flush 的改动列表；未成功 flush 过为空列表。
     */
    private List<FlushOp> lastFlushOps = List.of();

    /**
     * {@link #flush} 被调用次数（含栅栏不符返回 false）。
     */
    private int flushCount;

    /**
     * {@link #hGetAll} 按 key 的调用次数（测试核对加载读次数）。
     */
    private final Map<String, Integer> hGetAllCounts = new HashMap<>();

    /**
     * 模拟淘汰：删除 key 并清除 TTL。
     *
     * @param key Redis key
     */
    public void evict(String key) {
        entries.remove(key);
    }

    /**
     * @return 最近一次成功 flush 的改动列表快照
     */
    public List<FlushOp> lastFlushOps() {
        return lastFlushOps;
    }

    /**
     * @return {@link #flush} 被调用次数
     */
    public int flushCount() {
        return flushCount;
    }

    /**
     * @param key Redis key
     * @return {@link #hGetAll} 对该 key 的调用次数
     */
    public int hGetAllCount(String key) {
        return hGetAllCounts.getOrDefault(key, 0);
    }

    /**
     * 清零 {@link #hGetAll} 计数。
     */
    public void clearHGetAllCounts() {
        hGetAllCounts.clear();
    }

    /**
     * 当前剩余 TTL（毫秒）；不存在或未设过期返回 {@code null}。
     *
     * @param key Redis key
     * @return 剩余毫秒；无 TTL 为 {@code null}
     */
    public @Nullable Long ttlMs(String key) {
        Entry entry = entries.get(key);
        if (Objects.isNull(entry) || Objects.isNull(entry.deadlineMs)) {
            return null;
        }
        return Math.max(0L, entry.deadlineMs - System.currentTimeMillis());
    }

    @Override
    public RenewResult renew(String stateKey, String traceId, Duration conversationTtl,
                             @Nullable String sessionSharedKey, List<KeyTtl> extraKeys, long baseVersion) {
        Entry state = hashEntry(stateKey);
        String previous = state.hash.get("lastTraceId");
        state.hash.putIfAbsent("version", Long.toString(baseVersion));
        long version = Long.parseLong(state.hash.get("version")) + 1L;
        state.hash.put("version", Long.toString(version));
        state.hash.put("lastTraceId", JSON.obj2str(JsonNodeFactory.instance.stringNode(traceId)));
        state.hash.put("updatedAt", Long.toString(System.currentTimeMillis()));
        pexpire(stateKey, conversationTtl.toMillis());
        if (Strings.isNotBlank(sessionSharedKey)) {
            pexpire(sessionSharedKey, conversationTtl.toMillis());
        }
        for (int i = 0, j = extraKeys.size(); i < j; i++) {
            KeyTtl extra = extraKeys.get(i);
            pexpire(extra.key(), extra.ttlMs());
        }
        String decoded = Strings.isBlank(previous) ? null : JSON.str2obj(previous, String.class);
        return new RenewResult(version, decoded);
    }

    @Override
    public boolean flush(String stateKey, long fenceVersion, List<FlushOp> ops) {
        flushCount++;
        Entry state = entries.get(stateKey);
        String current = Objects.isNull(state) ? null : state.hash.get("version");
        if (Objects.isNull(current) || !Objects.equals(current, Long.toString(fenceVersion))) {
            return false;
        }
        for (int i = 0, j = ops.size(); i < j; i++) {
            apply(ops.get(i));
        }
        lastFlushOps = List.copyOf(ops);
        return true;
    }

    @Override
    public @Nullable String get(String key) {
        Entry entry = entries.get(key);
        if (Objects.isNull(entry) || Objects.isNull(entry.stringValue)) {
            return null;
        }
        return entry.stringValue;
    }

    @Override
    public Map<String, String> hGetAll(String key) {
        hGetAllCounts.merge(key, 1, Integer::sum);
        Entry entry = entries.get(key);
        if (Objects.isNull(entry) || Collections.isEmpty(entry.hash)) {
            return Map.of();
        }
        return Map.copyOf(entry.hash);
    }

    @Override
    public @Nullable String hGet(String key, String field) {
        Entry entry = entries.get(key);
        if (Objects.isNull(entry)) {
            return null;
        }
        return entry.hash.get(field);
    }

    @Override
    public List<String> lRange(String key, long start, long end) {
        Entry entry = entries.get(key);
        if (Objects.isNull(entry) || Collections.isEmpty(entry.list)) {
            return List.of();
        }
        int size = entry.list.size();
        int from = (int) (start < 0 ? size + start : start);
        int to = (int) (end < 0 ? size + end : end);
        from = Math.max(0, from);
        to = Math.min(size - 1, to);
        if (from > to || from >= size) {
            return List.of();
        }
        return List.copyOf(entry.list.subList(from, to + 1));
    }

    @Override
    public boolean exists(String key) {
        return entries.containsKey(key);
    }

    @Override
    public long countExisting(List<String> keys) {
        long count = 0L;
        for (int i = 0, j = keys.size(); i < j; i++) {
            if (entries.containsKey(keys.get(i))) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void del(String... keys) {
        for (int i = 0, j = keys.length; i < j; i++) {
            entries.remove(keys[i]);
        }
    }

    /**
     * 直接写入 Hash 字段（测试装载热组用）。
     *
     * @param key   Hash key
     * @param field 字段
     * @param value 值
     */
    public void hset(String key, String field, String value) {
        hashEntry(key).hash.put(field, value);
    }

    /**
     * 直接写入 String（测试装载热组用）。
     *
     * @param key   key
     * @param value 值
     */
    public void set(String key, String value) {
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        entry.stringValue = value;
        entry.hash = new LinkedHashMap<>();
        entry.list = new ArrayList<>();
    }

    private void apply(FlushOp op) {
        switch (op.op()) {
            case HSET -> {
                hashEntry(op.key()).hash.put(op.field(), op.value());
                if (Objects.nonNull(op.ttl())) {
                    pexpire(op.key(), op.ttl());
                }
            }
            case HDEL -> {
                Entry entry = entries.get(op.key());
                if (Objects.nonNull(entry)) {
                    entry.hash.remove(op.field());
                }
            }
            case SET -> {
                set(op.key(), op.value());
                if (Objects.nonNull(op.ttl())) {
                    pexpire(op.key(), op.ttl());
                }
            }
            case DEL -> entries.remove(op.key());
            case LPUSH_TRIM -> {
                Entry entry = entries.computeIfAbsent(op.key(), ignored -> new Entry());
                if (Objects.isNull(entry.list)) {
                    entry.list = new ArrayList<>();
                }
                entry.list.addFirst(op.value());
                if (Objects.nonNull(op.maxLen()) && entry.list.size() > op.maxLen()) {
                    entry.list = new ArrayList<>(entry.list.subList(0, op.maxLen().intValue()));
                }
            }
            case EXPIRE -> pexpire(op.key(), op.ttl());
            default -> throw new IllegalArgumentException("unknown flush op: " + op.op());
        }
    }

    private Entry hashEntry(String key) {
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        if (Objects.isNull(entry.hash)) {
            entry.hash = new LinkedHashMap<>();
        }
        entry.stringValue = null;
        entry.list = new ArrayList<>();
        return entry;
    }

    private void pexpire(String key, @Nullable Long ttlMs) {
        if (Objects.isNull(ttlMs)) {
            return;
        }
        Entry entry = entries.get(key);
        if (Objects.isNull(entry)) {
            return;
        }
        entry.deadlineMs = System.currentTimeMillis() + ttlMs;
    }

    /**
     * 单个 key 的内存内容与过期时刻。
     */
    private static final class Entry {

        /**
         * String 值；Hash / List key 为空。
         */
        private @Nullable String stringValue;

        /**
         * Hash 字段；String / List key 为空 Map。
         */
        private Map<String, String> hash = new LinkedHashMap<>();

        /**
         * List 元素；表头为最新。
         */
        private List<String> list = new ArrayList<>();

        /**
         * 过期绝对时刻（epoch ms）；未设过期为空。
         */
        private @Nullable Long deadlineMs;
    }
}
