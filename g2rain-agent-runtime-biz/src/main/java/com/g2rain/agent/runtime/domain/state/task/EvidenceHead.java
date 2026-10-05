package com.g2rain.agent.runtime.domain.state.task;


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
 * @author alpha
 * @since 2026/10/3
 */
public record EvidenceHead(String evidenceId, String toolName, SideEffect sideEffect, EvidenceStatus status,
                           @Nullable Boolean businessOk, @Nullable Boolean empty,
                           @Nullable Map<String, Integer> extractCounts, FailureKind failureKind,
                           @Nullable String idempotencyKey, @Nullable String argsHash, @Nullable String argsBrief,
                           @Nullable String resultBrief, @Nullable String errorCode, @Nullable String entities,
                           @Nullable String payloadId, int round, String traceId) {

}
