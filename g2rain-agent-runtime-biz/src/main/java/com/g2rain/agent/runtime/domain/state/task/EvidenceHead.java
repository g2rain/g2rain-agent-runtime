package com.g2rain.agent.runtime.domain.state.task;


import com.g2rain.agent.runtime.domain.tool.ToolResult;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * 节点证据的轻量索引和摘要。
 *
 * <p>对象保留调用结果判定、幂等键、抽取统计和定位信息；较大的原始报文通过
 * {@code payloadId} 单独存取。这样热状态可以快速完成完成条件判断，同时仍能按
 * {@code evidenceId} 回查完整证据。</p>
 *
 * @param evidenceId     证据 id，同时是 tool_call_record.call_id
 * @param toolName       工具名
 * @param sideEffect     NONE / WRITE
 * @param status         调用态
 * @param businessOk     业务是否成功；无法判断为空
 * @param empty          结构空或 extracts 全 0
 * @param extractCounts  各 extracts.key 的条数
 * @param failureKind    失败分类；成功为 NONE
 * @param idempotencyKey 写工具业务幂等键
 * @param argsHash       业务入参摘要
 * @param argsBrief      参数摘要文案
 * @param resultBrief    结果摘要文案
 * @param errorCode      错误码
 * @param entities       抽出的实体（序列化）
 * @param payloadId      超大报文落库行 id
 * @param round          所在 ReAct 轮次
 * @param traceId        本轮 traceId
 * @param detail         未成功（执行失败或业务失败）时返回给模型的完整说明"原因。下一步：…"；成功为 {@code null}
 * @author alpha
 * @since 2026/10/3
 */
public record EvidenceHead(String evidenceId, String toolName, SideEffect sideEffect, EvidenceStatus status,
                           @Nullable Boolean businessOk, @Nullable Boolean empty,
                           @Nullable Map<String, Integer> extractCounts, FailureKind failureKind,
                           @Nullable String idempotencyKey, @Nullable String argsHash, @Nullable String argsBrief,
                           @Nullable String resultBrief, @Nullable String errorCode, @Nullable String entities,
                           @Nullable String payloadId, int round, String traceId, @Nullable String detail) {

    /**
     * 是否算调用完成：调用成功且业务未判失败；空结果也算完成。
     *
     * @return 完成时为 {@code true}
     */
    public boolean done() {
        return status == EvidenceStatus.SUCCESS && !Boolean.FALSE.equals(businessOk);
    }

    /**
     * 改调用态。
     *
     * @param status 新状态
     * @return 新证据头
     */
    public EvidenceHead withStatus(EvidenceStatus status) {
        return withStatus(status, failureKind, errorCode);
    }

    /**
     * 改调用态、失败分类与错误码。
     *
     * @param status    新状态
     * @param kind      失败分类
     * @param errorCode 错误码；可空
     * @return 新证据头
     */
    public EvidenceHead withStatus(EvidenceStatus status, FailureKind kind, @Nullable String errorCode) {
        return new EvidenceHead(evidenceId, toolName, sideEffect, status, businessOk, empty, extractCounts, kind,
            idempotencyKey, argsHash, argsBrief, resultBrief, errorCode, entities, payloadId, round, traceId, detail);
    }

    /**
     * 收口未知写证据：改调用态、业务结论与失败分类。
     *
     * @param status     新状态
     * @param businessOk 业务是否成功；可空
     * @param kind       失败分类
     * @return 新证据头
     */
    public EvidenceHead withOutcome(EvidenceStatus status, @Nullable Boolean businessOk, FailureKind kind) {
        return new EvidenceHead(evidenceId, toolName, sideEffect, status, businessOk, empty, extractCounts, kind,
            idempotencyKey, argsHash, argsBrief, resultBrief, errorCode, entities, payloadId, round, traceId, detail);
    }

    /**
     * 写入调用结果；身份、入参与定位信息保持不变。
     *
     * @param result      工具结果
     * @param resultBrief 已裁剪的结果摘要；可空
     * @param entities    已裁剪的实体；可空
     * @param detail      未成功时的完整说明；可空
     * @return 新证据头
     */
    public EvidenceHead withResult(ToolResult result, @Nullable String resultBrief, @Nullable String entities,
                                   @Nullable String detail) {
        return new EvidenceHead(evidenceId, toolName, sideEffect, result.status(), result.businessOk(), result.empty(),
            result.extractCounts(), result.failureKind(), idempotencyKey, argsHash, argsBrief, resultBrief,
            result.errorCode(), entities, payloadId, round, traceId, detail);
    }

    /**
     * 改报文 id。
     *
     * @param payloadId 报文 id；可空表示内联
     * @return 新证据头
     */
    public EvidenceHead withPayloadId(@Nullable String payloadId) {
        return new EvidenceHead(evidenceId, toolName, sideEffect, status, businessOk, empty, extractCounts, failureKind,
            idempotencyKey, argsHash, argsBrief, resultBrief, errorCode, entities, payloadId, round, traceId, detail);
    }
}
