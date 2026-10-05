package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.engine.store.FlushOp;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 按 key 类型汇总本轮 flush 写入体量，供运维判断是否做大报文压缩等后续项。
 *
 * @author alpha
 * @since 2026/10/4
 */
@Slf4j
public final class StateSizeProbe {

    /**
     * 静态探针，禁止实例化。
     */
    private StateSizeProbe() {

    }

    /**
     * 统计并打日志：payload / evidence / space / prior / writes。
     *
     * @param conversationId 会话 id；可空
     * @param taskId         任务 id；可空
     * @param ops            本轮 flush 改动
     */
    public static void record(@Nullable String conversationId, @Nullable String taskId, List<FlushOp> ops) {
        if (!log.isInfoEnabled() || Collections.isEmpty(ops)) {
            return;
        }
        long payload = 0L;
        long evidence = 0L;
        long space = 0L;
        long prior = 0L;
        long writes = 0L;
        for (FlushOp op : ops) {
            if (skip(op.op())) {
                continue;
            }
            long n = bytes(op);
            String key = Strings.defaultIfBlank(op.key(), "");
            if (key.contains(":payload:")) {
                payload += n;
            } else if (key.contains(":evidence")) {
                evidence += n;
            } else if (key.contains(":space")) {
                space += n;
            } else if (key.contains(":prior")) {
                prior += n;
            } else if (key.contains(":writes")) {
                writes += n;
            }
        }
        if (payload == 0L && evidence == 0L && space == 0L && prior == 0L && writes == 0L) {
            return;
        }
        log.info("[StateSize] conversationId={} taskId={} payloadBytes={} evidenceBytes={} spaceBytes={} priorBytes={} writesBytes={} ops={}",
            conversationId, taskId, payload, evidence, space, prior, writes, ops.size());
    }

    /**
     * 删除与仅续期不计入写入体量。
     *
     * @param op flush 操作名；可空
     * @return 是否跳过统计
     */
    private static boolean skip(@Nullable String op) {
        return "DEL".equals(op) || "HDEL".equals(op) || "EXPIRE".equals(op);
    }

    /**
     * 统计 field 与 value 的 UTF-8 字节数。
     *
     * @param op 一条 flush 改动
     * @return UTF-8 字节数；缺 field/value 时为 0
     */
    private static long bytes(FlushOp op) {
        long n = 0L;
        if (Strings.isNotBlank(op.field())) {
            n += op.field().getBytes(StandardCharsets.UTF_8).length;
        }
        if (Strings.isNotBlank(op.value())) {
            n += op.value().getBytes(StandardCharsets.UTF_8).length;
        }
        return n;
    }
}
