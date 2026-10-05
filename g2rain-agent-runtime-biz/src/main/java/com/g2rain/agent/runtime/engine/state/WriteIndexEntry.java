package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;

/**
 * 任务级写证据索引条目：{@code task:&lt;tid&gt;:writes} Hash 的 value。
 *
 * <p>field 为 {@code toolName|idempotencyKey}。{@link #live()} 与预写去重口径一致：
 * 算成功的 SUCCESS，以及 STARTED / UNKNOWN / UNKNOWN_PRE_CALL 占用幂等槽。</p>
 *
 * @param evidenceId 证据 id
 * @param status     证据状态
 * @param businessOk 业务是否成功；可空
 * @author alpha
 * @since 2026/10/4
 */
public record WriteIndexEntry(String evidenceId, EvidenceStatus status, @Nullable Boolean businessOk) {

    /**
     * Hash field：{@code toolName|idempotencyKey}。
     *
     * @param toolName       工具名
     * @param idempotencyKey 生效幂等键
     * @return 字段名
     */
    public static String field(String toolName, String idempotencyKey) {
        return Strings.defaultIfBlank(toolName, "") + "|" + Strings.defaultIfBlank(idempotencyKey, "");
    }

    /**
     * 由证据摘要构造索引；缺 evidenceId / 幂等键 / 工具名时返回 {@code null}。
     *
     * @param head 证据摘要
     * @return 索引条目；字段不全为 {@code null}
     */
    public static @Nullable WriteIndexEntry of(EvidenceHead head) {
        if (Strings.isBlank(head.evidenceId()) || Strings.isBlank(head.idempotencyKey()) || Strings.isBlank(head.toolName())) {
            return null;
        }

        return new WriteIndexEntry(head.evidenceId(), head.status(), head.businessOk());
    }

    /**
     * 是否仍占用幂等槽（与预写去重同一口径）。
     *
     * @return {@code true} 表示不得再发起同键写调用
     */
    public boolean live() {
        if (status == EvidenceStatus.SUCCESS) {
            return !Boolean.FALSE.equals(businessOk);
        }

        return status == EvidenceStatus.STARTED
            || status == EvidenceStatus.UNKNOWN
            || status == EvidenceStatus.UNKNOWN_PRE_CALL;
    }
}
