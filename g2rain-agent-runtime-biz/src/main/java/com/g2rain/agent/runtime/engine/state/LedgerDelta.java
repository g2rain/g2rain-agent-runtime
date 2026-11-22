package com.g2rain.agent.runtime.engine.state;


import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 一次 flush 待提交到 {@link FlushLedger} 的确认内容。
 *
 * <p>组装 ops 时记下对比过的 Hash / String 终态，以及被整组 DEL 的 key；
 * 仅在 {@code store.flush} 成功后由 ledger commit。</p>
 *
 * @author alpha
 * @since 2026/11/22
 */
final class LedgerDelta {

    /**
     * 本轮对比过的 Hash：key → 编码后的全部字段。
     */
    private final Map<String, Map<String, String>> hashes = new LinkedHashMap<>();

    /**
     * 本轮对比过的 String：key → 编码后的值。
     */
    private final Map<String, String> strings = new LinkedHashMap<>();

    /**
     * 本轮被 DEL 的 key。
     */
    private final Set<String> removed = new LinkedHashSet<>();

    /**
     * 记下某 Hash 本次编码后的全部字段，flush 成功后成为已确认内容。
     *
     * @param key     Redis key
     * @param current 编码结果
     */
    void confirmHash(String key, Map<String, String> current) {
        hashes.put(key, Map.copyOf(current));
    }

    /**
     * 记下某 String 本次编码后的值。
     *
     * @param key     Redis key
     * @param current 编码结果
     */
    void confirmString(String key, String current) {
        strings.put(key, current);
    }

    /**
     * 记下被整组删除的 key。
     *
     * @param key Redis key
     */
    void remove(String key) {
        removed.add(key);
    }

    /**
     * @return Hash 确认表
     */
    Map<String, Map<String, String>> hashes() {
        return hashes;
    }

    /**
     * @return String 确认表
     */
    Map<String, String> strings() {
        return strings;
    }

    /**
     * @return 被删除的 key
     */
    Set<String> removed() {
        return removed;
    }
}
