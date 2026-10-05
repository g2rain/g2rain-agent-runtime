package com.g2rain.agent.runtime.domain.tool;


import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 一次工具调用的归一化结果。
 *
 * <p>结果同时保留执行状态、原始报文、业务成功判定、空结果判定、抽取统计和错误信息，
 * 供写操作结算、完成判定和审计记录使用。原始报文可能因敏感数据策略而为空，但摘要和状态
 * 仍可独立存在。</p>
 *
 * @param status        调用态
 * @param payload       原始返回；无报文为空
 * @param businessOk    业务是否成功；无法判断为空
 * @param empty         结构空或 extracts 全 0
 * @param extractCounts 各 extracts.key 条数
 * @param failureKind   失败分类；成功为 NONE
 * @param errorCode     错误码
 * @param resultBrief   结果摘要
 * @param entities      extracts[].entity 候选 JSON；无可空
 * @author alpha
 * @since 2026/10/3
 */
public record ToolResult(EvidenceStatus status, @Nullable JsonNode payload, @Nullable Boolean businessOk,
                         @Nullable Boolean empty, @Nullable Map<String, Integer> extractCounts, FailureKind failureKind,
                         @Nullable String errorCode, @Nullable String resultBrief, @Nullable String entities) {

    /**
     * 创建不带实体候选的结果。
     *
     * <p>适用于不抽取实体的工具；其它字段与完整构造器含义相同。</p>
     *
     * @param status        调用态
     * @param payload       原始返回；无报文为空
     * @param businessOk    业务是否成功；无法判断为空
     * @param empty         结构空或 extracts 全 0
     * @param extractCounts 各 extracts.key 条数
     * @param failureKind   失败分类；成功为 NONE
     * @param errorCode     错误码
     * @param resultBrief   结果摘要
     */
    public ToolResult(EvidenceStatus status, @Nullable JsonNode payload, @Nullable Boolean businessOk,
                      @Nullable Boolean empty, @Nullable Map<String, Integer> extractCounts, FailureKind failureKind,
                      @Nullable String errorCode, @Nullable String resultBrief) {
        this(status, payload, businessOk, empty, extractCounts, failureKind, errorCode, resultBrief, null);
    }
}
