package com.g2rain.agent.runtime.domain.state.progress;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * 下发给客户端的步骤快照；不含排查信息 {@code extra}。
 *
 * @param stepId       步骤标识
 * @param parentStepId 上级步骤；顶层为空
 * @param stage        环节
 * @param status       状态
 * @param title        标题
 * @param detail       补充说明；可空
 * @param nodeId       所属节点；可空
 * @param round        ReAct 轮次；可空
 * @param elapsedMs    耗时毫秒；仅结束后有值
 * @param errorCode    失败错误码；仅 FAILED
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record StepView(String stepId, @Nullable String parentStepId, StepStage stage, StepStatus status,
                       String title, @Nullable String detail, @Nullable String nodeId, @Nullable Integer round,
                       @Nullable Integer elapsedMs, @Nullable String errorCode) {
}
