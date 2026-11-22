package com.g2rain.agent.runtime.engine.state;


import java.util.List;
import java.util.Map;

/**
 * Hash 对比结果：待 HSET 的字段与待 HDEL 的字段。
 *
 * @param put    字段 → 新值
 * @param delete 待删除的字段名
 * @author alpha
 * @since 2026/11/22
 */
record HashDiff(Map<String, String> put, List<String> delete) {

    /**
     * @return 无写入也无删除
     */
    boolean empty() {
        return put.isEmpty() && delete.isEmpty();
    }
}
