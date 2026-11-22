package com.g2rain.agent.runtime.engine.state;


import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 本轮已确认的 Redis Hash / String 内容，供 flush 按变更对比。
 *
 * <p>加载或上次 flush 成功后登记；未登记的 key 视为空。本轮结束即随视图丢弃。</p>
 *
 * @author alpha
 * @since 2026/11/22
 */
final class FlushLedger {

    /**
     * 已确认的 Hash：Redis key → 字段表。
     */
    private final Map<String, Map<String, String>> hashes = new LinkedHashMap<>();

    /**
     * 已确认的 String：Redis key → 值。
     */
    private final Map<String, String> strings = new LinkedHashMap<>();

    /**
     * 加载或 flush 成功后，记下某个 Hash 当前在 Redis 中的全部字段。
     *
     * @param key    Redis key
     * @param fields 字段表
     */
    void seedHash(String key, Map<String, String> fields) {
        hashes.put(key, new LinkedHashMap<>(fields));
    }

    /**
     * 加载或 flush 成功后，记下某个 String 当前值；{@code null} 按空串登记。
     *
     * @param key   Redis key
     * @param value 值；可空
     */
    void seedString(String key, @Nullable String value) {
        strings.put(key, Objects.requireNonNullElse(value, ""));
    }

    /**
     * 与上次确认的内容比较：返回要 HSET 的字段和要 HDEL 的字段。未登记过的 key 视为空。
     *
     * @param key     Redis key
     * @param current 本次编码结果
     * @return 差异
     */
    HashDiff diff(String key, Map<String, String> current) {
        Map<String, String> previous = fields(key);
        Map<String, String> put = putDiff(key, current);
        List<String> delete = new ArrayList<>();
        for (String field : previous.keySet()) {
            if (!current.containsKey(field)) {
                delete.add(field);
            }
        }
        return new HashDiff(put, delete);
    }

    /**
     * 只返回相对已确认内容新增或变化的字段（不含删除）。未登记过的 key 视为空。
     *
     * @param key     Redis key
     * @param current 本次编码结果
     * @return 待 HSET 的字段
     */
    Map<String, String> putDiff(String key, Map<String, String> current) {
        Map<String, String> previous = fields(key);
        Map<String, String> put = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : current.entrySet()) {
            if (!Objects.equals(previous.get(entry.getKey()), entry.getValue())) {
                put.put(entry.getKey(), entry.getValue());
            }
        }
        return put;
    }

    /**
     * 已确认的 Hash 字段表；未登记为空 Map。
     *
     * @param key Redis key
     * @return 字段表副本
     */
    Map<String, String> fields(String key) {
        Map<String, String> previous = hashes.get(key);
        if (Objects.isNull(previous)) {
            return Map.of();
        }
        return new LinkedHashMap<>(previous);
    }

    /**
     * String 值是否与上次确认的不同；未登记过视为不同。
     *
     * @param key     Redis key
     * @param current 本次编码结果
     * @return 不同时为 {@code true}
     */
    boolean changed(String key, String current) {
        if (!strings.containsKey(key)) {
            return true;
        }
        return !Objects.equals(strings.get(key), current);
    }

    /**
     * 本次 flush 成功后提交：把本次写入的内容记为已确认；被 DEL 的 key 移除。
     *
     * @param delta 本轮对比过的终态与删除
     */
    void commit(LedgerDelta delta) {
        for (Map.Entry<String, Map<String, String>> entry : delta.hashes().entrySet()) {
            seedHash(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : delta.strings().entrySet()) {
            seedString(entry.getKey(), entry.getValue());
        }
        for (String key : delta.removed()) {
            hashes.remove(key);
            strings.remove(key);
        }
    }

    /**
     * 测试用：某 Hash 是否已登记。
     *
     * @param key Redis key
     * @return 已登记为 {@code true}
     */
    boolean hasHash(String key) {
        return hashes.containsKey(key);
    }

    /**
     * 测试用：空白则视为未登记或空串。
     *
     * @param key Redis key
     * @return 已确认值；未登记为 {@code null}
     */
    @Nullable String stringOf(String key) {
        return strings.get(key);
    }
}
