package com.g2rain.agent.runtime.domain.state.progress;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * 本轮的一个执行步骤；开始时创建，结束时原地更新状态、说明与耗时。
 */
@Getter
@Setter
public class ProgressStep {

    /**
     * 步骤标识；本轮内唯一。
     */
    private String stepId;

    /**
     * 上级步骤；顶层为空。
     */
    private @Nullable String parentStepId;

    /**
     * 本轮内开始顺序。
     */
    private int seq;

    /**
     * 环节。
     */
    private StepStage stage;

    /**
     * 状态。
     */
    private StepStatus status = StepStatus.RUNNING;

    /**
     * 标题。
     */
    private String title;

    /**
     * 补充说明；可空。
     */
    private @Nullable String detail;

    /**
     * 所属任务；可空。
     */
    private @Nullable String taskId;

    /**
     * 所属节点；可空。
     */
    private @Nullable String nodeId;

    /**
     * ReAct 轮次；可空。
     */
    private @Nullable Integer round;

    /**
     * 失败错误码；仅 FAILED。
     */
    private @Nullable String errorCode;

    /**
     * 排查信息（token、参数摘要、上游原文）；只入库，不下发。
     */
    private @Nullable Map<String, Object> extra;

    /**
     * 开始时间 epoch ms。
     */
    private long startedAt;

    /**
     * 耗时毫秒；仅结束后有值。
     */
    private @Nullable Integer elapsedMs;

    /**
     * @return 下发给客户端的快照
     */
    public StepView view() {
        return new StepView(stepId, parentStepId, stage, status, title, detail, nodeId, round, elapsedMs, errorCode);
    }
}
