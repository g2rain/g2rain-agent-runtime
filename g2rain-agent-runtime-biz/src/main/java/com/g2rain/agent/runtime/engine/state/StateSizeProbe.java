package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.engine.store.FlushOp;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 按 key 类型汇总本轮 flush 写入体量，输出为 {@code [Trace][STATE] size} 轨迹，供运维判断是否做大报文压缩等后续项。
 *
 * <p>会话与任务 ID 由本轮轨迹作用域写入 MDC，不在事件字段中重复。</p>
 *
 * @author alpha
 * @since 2026/10/4
 */
public final class StateSizeProbe {

    /**
     * 静态探针，禁止实例化。
     */
    private StateSizeProbe() {

    }

    /**
     * 统计并输出轨迹：payload / evidence / space / prior / writes；无写入时不输出。
     *
     * @param ops 本轮 flush 改动
     */
    public static void record(List<FlushOp> ops) {
        if (!Trace.enabled(TraceStage.STATE) || Collections.isEmpty(ops)) {
            return;
        }
        long payload = 0L;
        long evidence = 0L;
        long space = 0L;
        long prior = 0L;
        long writes = 0L;
        // 只统计写操作，按 Redis key 后缀归类累计字节数
        for (FlushOp op : ops) {
            if (!op.op().writes()) {
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
        Trace.at(TraceStage.STATE, "size")
            .kv("payloadBytes", payload)
            .kv("evidenceBytes", evidence)
            .kv("spaceBytes", space)
            .kv("priorBytes", prior)
            .kv("writesBytes", writes)
            .kv("opCount", ops.size())
            .info();
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
