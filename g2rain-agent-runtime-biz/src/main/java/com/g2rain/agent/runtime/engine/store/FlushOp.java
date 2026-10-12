package com.g2rain.agent.runtime.engine.store;


import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * flush.lua 单条改动。
 * <p>{@code LPUSH_TRIM} 的 ttl 表示保留条数，其余 op 的 ttl 为 PEXPIRE 毫秒。</p>
 *
 * @param op    操作
 * @param key   Redis key
 * @param field Hash 字段；非 HSET/HDEL 为空
 * @param value 写入或删除的值
 * @param ttl   过期毫秒或 List 保留条数
 * @author alpha
 * @since 2026/10/3
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlushOp(Op op, String key, @Nullable String field, @Nullable String value, @Nullable Long ttl) {

    /**
     * flush.lua 支持的操作；名称即脚本里的分支名。
     */
    public enum Op {

        /**
         * Hash 设字段
         */
        HSET(true),

        /**
         * Hash 删字段
         */
        HDEL(false),

        /**
         * 写 String
         */
        SET(true),

        /**
         * 删 key
         */
        DEL(false),

        /**
         * 左侧推入并裁剪长度
         */
        LPUSH_TRIM(true),

        /**
         * 设过期
         */
        EXPIRE(false);

        /**
         * 是否写入内容（删除与续期不写）
         */
        private final boolean writes;

        Op(boolean writes) {
            this.writes = writes;
        }

        /**
         * @return 是否写入内容
         */
        public boolean writes() {
            return writes;
        }
    }

    /**
     * Hash 设字段。
     *
     * @param key   Hash key
     * @param field 字段
     * @param value 值
     * @param ttlMs 可选 PEXPIRE
     * @return 改动
     */
    public static FlushOp hset(String key, String field, String value, @Nullable Long ttlMs) {
        return new FlushOp(Op.HSET, key, field, value, ttlMs);
    }

    /**
     * Hash 删字段。
     *
     * @param key   Hash key
     * @param field 字段
     * @return 改动
     */
    public static FlushOp hdel(String key, String field) {
        return new FlushOp(Op.HDEL, key, field, null, null);
    }

    /**
     * 写 String。
     *
     * @param key   key
     * @param value 值
     * @param ttlMs 可选 PX
     * @return 改动
     */
    public static FlushOp set(String key, String value, @Nullable Long ttlMs) {
        return new FlushOp(Op.SET, key, null, value, ttlMs);
    }

    /**
     * 删 key。
     *
     * @param key key
     * @return 改动
     */
    public static FlushOp del(String key) {
        return new FlushOp(Op.DEL, key, null, null, null);
    }

    /**
     * 左侧推入并裁剪长度。
     *
     * @param key    List key
     * @param value  元素
     * @param maxLen 保留条数
     * @return 改动
     */
    public static FlushOp lpushTrim(String key, String value, long maxLen) {
        return new FlushOp(Op.LPUSH_TRIM, key, null, value, maxLen);
    }

    /**
     * 给 key 设过期。
     *
     * @param key   key
     * @param ttlMs 过期毫秒
     * @return 改动
     */
    public static FlushOp expire(String key, long ttlMs) {
        return new FlushOp(Op.EXPIRE, key, null, null, ttlMs);
    }
}
