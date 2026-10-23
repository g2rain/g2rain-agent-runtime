package com.g2rain.agent.runtime.event;

import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.progress.ProgressStep;
import com.g2rain.agent.runtime.domain.state.progress.StepStage;
import com.g2rain.agent.runtime.domain.state.progress.StepStatus;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.support.FailureCategory;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 执行步骤的记录与推送：每次开始或结束都更新本轮 {@link com.g2rain.agent.runtime.domain.state.progress.TurnProgress}，
 * 并推一帧 PROGRESS。
 *
 * <p>本轮由 {@link #TURN} 绑定（{@code ConversationServiceImpl} 在会话锁内绑定），各环节无需传递本轮视图；
 * 未绑定时（如脱离对话调用模型）所有方法静默跳过。</p>
 */
@Component
@RequiredArgsConstructor
public class StepEvents {

    /**
     * 当前轮次。
     */
    public static final ScopedValue<TurnScope> TURN = ScopedValue.newInstance();

    /**
     * 本轮事件发布器。
     */
    private final EventPublisher eventPublisher;

    /**
     * 开启一个步骤。
     *
     * @param stage 环节
     * @param title 标题
     * @return 步骤标识；未绑定本轮时为空
     */
    public @Nullable String start(StepStage stage, String title) {
        return start(stage, title, null, null);
    }

    /**
     * 开启一个步骤。
     *
     * @param stage  环节
     * @param title  标题
     * @param nodeId 所属节点；可空
     * @param round  ReAct 轮次；可空
     * @return 步骤标识；未绑定本轮时为空
     */
    public @Nullable String start(StepStage stage, String title, @Nullable String nodeId, @Nullable Integer round) {
        if (!TURN.isBound()) {
            return null;
        }
        TurnScope turn = TURN.get();
        String taskId = turn.task().map(TaskMetadata::getTaskId).orElse(null);
        List<ProgressStep> changed = turn.progress().start(stage, title, taskId, nodeId, round);
        publish(turn, changed);
        return changed.getLast().getStepId();
    }

    /**
     * 步骤完成。
     *
     * @param stepId 步骤标识；为空时跳过
     * @param detail 补充说明；可空
     */
    public void done(@Nullable String stepId, @Nullable String detail) {
        done(stepId, detail, null);
    }

    /**
     * 步骤完成。
     *
     * @param stepId 步骤标识；为空时跳过
     * @param detail 补充说明；可空
     * @param extra  排查信息；可空
     */
    public void done(@Nullable String stepId, @Nullable String detail, @Nullable Map<String, Object> extra) {
        finish(stepId, StepStatus.DONE, detail, null, extra);
    }

    /**
     * 步骤失败。
     *
     * @param stepId    步骤标识；为空时跳过
     * @param detail    失败说明；可空
     * @param errorCode 错误码；可空
     * @param extra     排查信息；可空
     */
    public void fail(@Nullable String stepId, @Nullable String detail, @Nullable String errorCode,
                     @Nullable Map<String, Object> extra) {
        finish(stepId, StepStatus.FAILED, detail, errorCode, extra);
    }

    /**
     * 步骤因异常失败：说明取失败分类文案，排查信息记异常原文。
     *
     * @param stepId 步骤标识；为空时跳过
     * @param ex     异常
     */
    public void fail(@Nullable String stepId, RuntimeException ex) {
        fail(stepId, FailureCategory.of(ex).text(), RuntimeUtils.businessCode(ex),
            Map.of("error", Objects.toString(ex.getMessage(), ex.getClass().getSimpleName())));
    }

    /**
     * 结束全部进行中的步骤；本轮出结果前调用，保证时间线完整。
     *
     * @param failed    本轮是否失败
     * @param errorCode 失败错误码；可空
     */
    public void finishAll(boolean failed, @Nullable String errorCode) {
        if (!TURN.isBound()) {
            return;
        }
        TurnScope turn = TURN.get();
        publish(turn, turn.progress().finishAll(failed ? StepStatus.FAILED : StepStatus.DONE, errorCode));
    }

    /**
     * 结束步骤并推送。
     */
    private void finish(@Nullable String stepId, StepStatus status, @Nullable String detail,
                        @Nullable String errorCode, @Nullable Map<String, Object> extra) {
        if (Objects.isNull(stepId) || !TURN.isBound()) {
            return;
        }
        TurnScope turn = TURN.get();
        publish(turn, turn.progress().finish(stepId, status, detail, errorCode, extra));
    }

    /**
     * 逐个推送变化的步骤快照。
     */
    private void publish(TurnScope turn, List<ProgressStep> changed) {
        changed.forEach(step -> eventPublisher.publish(TurnEvents.progress(turn, step.view())));
    }
}
