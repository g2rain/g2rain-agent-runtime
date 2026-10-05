package com.g2rain.agent.runtime.support;


import com.g2rain.common.utils.Collections;
import lombok.Getter;

/**
 * Runtime 使用的 Redis 键模板。
 *
 * <p>每个常量持有带 {@code %s} 的格式串，经 {@link #format} 填入租户、会话或任务 id。
 * 本枚举只产生键名，不读写 Redis。</p>
 *
 * <pre>{@code
 * String lock = RedisKeys.LOCK.format(conversationId);
 * String meta = RedisKeys.METADATA.format(tenantId, conversationId, taskId);
 * }</pre>
 *
 * @author alpha
 * @since 2026/09/02
 */
@Getter
public enum RedisKeys {

    /**
     * 会话锁：按 conversationId 互斥，保证同一会话串行改状态。
     * <p>例：{@code RedisKeys.LOCK.format(cid)}。</p>
     */
    LOCK("g2rain:rt:lock:conv:%s"),

    /**
     * 会话栅栏 Hash：记录本轮推进游标，防止并发请求互相覆盖。
     */
    STATE("g2rain:rt:{%s:%s}:state"),

    /**
     * 会话级共享 Hash：跨 Task 可复用的会话记忆切片。
     */
    SESSION_SHARED("g2rain:rt:{%s:%s}:shared"),

    /**
     * 交互历史 List：USER/AGENT 实际发生过的发言。
     */
    DIALOGUE("g2rain:rt:{%s:%s}:dialogue"),

    /**
     * 已结束任务的回合摘要 List。
     */
    RECORDS("g2rain:rt:{%s:%s}:records"),

    /**
     * 实体索引 Hash：会话记忆里按指代可匹配的实体。
     */
    ENTITIES("g2rain:rt:{%s:%s}:entities"),

    /**
     * 敏感值明文：与任务记忆中的脱敏占位对应，按需解密。
     */
    SECRET("g2rain:rt:{%s:%s}:secret:%s"),

    /**
     * 任务元数据 Hash：粗状态、当前节点、Agent 等哨兵字段。
     */
    METADATA("g2rain:rt:{%s:%s}:task:%s:metadata"),

    /**
     * 任务图 Hash：节点与边的热态。
     */
    GRAPH("g2rain:rt:{%s:%s}:task:%s:graph"),

    /**
     * 任务检查点：挂起原因、允许的恢复动作和待处理内容。
     */
    CHECKPOINT("g2rain:rt:{%s:%s}:task:%s:checkpoint"),

    /**
     * 任务级共享 Hash：本 Task 内节点之间可见的任务记忆。
     */
    SHARED("g2rain:rt:{%s:%s}:task:%s:shared"),

    /**
     * 任务前情：上一 Task 留给下一 Task 的可过期衔接包。
     */
    PRIOR("g2rain:rt:{%s:%s}:task:%s:prior"),

    /**
     * 任务级写证据索引 Hash（field = toolName|idempotencyKey）。
     */
    WRITES("g2rain:rt:{%s:%s}:task:%s:writes"),

    /**
     * 节点空间 Hash：本节点的任务记忆、草稿和产出。
     */
    SPACE("g2rain:rt:{%s:%s}:task:%s:node:%s:space"),

    /**
     * 节点证据摘要 Hash：工具调用的轻量索引。
     */
    EVIDENCE("g2rain:rt:{%s:%s}:task:%s:node:%s:evidence"),

    /**
     * 证据报文正文：超大原始载荷按 payloadId 单独存放。
     */
    PAYLOAD("g2rain:rt:{%s:%s}:task:%s:payload:%s");

    /**
     * Redis 键格式模板，含 {@code %s} 占位符，构造后不变。
     */
    private final String key;

    /**
     * 构造：写入本常量对应的键模板。
     *
     * @param key Redis 键格式规则
     */
    RedisKeys(String key) {
        this.key = key;
    }

    /**
     * 用传入值替换模板中的占位符。
     *
     * <p>未传值时返回模板原文，便于查看规则本身。</p>
     *
     * <pre>{@code
     * RedisKeys.LOCK.format("c1"); // g2rain:rt:lock:conv:c1
     * RedisKeys.LOCK.format();     // g2rain:rt:lock:conv:%s
     * }</pre>
     *
     * @param values 按顺序替换 {@code %s} 的值
     * @return 格式化后的 Redis 键，或未传参时的模板串
     */
    public String format(String... values) {
        // 未传占位值时返回模板原文，便于查看键规则
        if (Collections.isEmpty(values)) {
            return this.key;
        }

        return String.format(this.key, (Object[]) values);
    }
}
