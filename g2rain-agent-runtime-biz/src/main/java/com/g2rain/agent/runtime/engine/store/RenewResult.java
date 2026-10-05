package com.g2rain.agent.runtime.engine.store;


import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;

/**
 * renew.lua 返回：新栅栏版本与写入前的 lastTraceId。
 *
 * @param version         本轮栅栏
 * @param previousTraceId 写入前的 lastTraceId；空表示上一轮已正常收尾
 * @author alpha
 * @since 2026/10/3
 */
public record RenewResult(long version, @Nullable String previousTraceId) {

    /**
     * 上一轮没有正常收尾（lastTraceId 非空），本轮须做中断规整。
     *
     * @return 需要规整为 {@code true}
     */
    public boolean interrupted() {
        return Strings.isNotBlank(previousTraceId);
    }
}
