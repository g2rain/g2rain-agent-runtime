package com.g2rain.agent.runtime.domain.state.progress;

import com.g2rain.agent.runtime.support.RuntimeUtils;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 本轮执行过程：按开始顺序记录步骤，并维护进行中步骤的嵌套关系。
 *
 * <p>本轮单线程推进：新开步骤的上级是当前最内层的进行中步骤；结束一个步骤时，
 * 其下仍在进行的子步骤按同一状态一并结束。开启某环节的新步骤时，仍在进行的同环节步骤
 * （连同其子步骤）自动完成，如 ReAct 的下一轮开始时上一轮随之完成。</p>
 */
public class TurnProgress {

    /**
     * 全部步骤，按开始顺序。
     */
    private final Map<String, ProgressStep> steps = new LinkedHashMap<>();

    /**
     * 进行中的步骤，栈顶为最内层。
     */
    private final Deque<ProgressStep> open = new ArrayDeque<>();

    /**
     * 是否已落库。
     */
    private boolean persisted;

    /**
     * 开启一个步骤。
     *
     * @param stage  环节
     * @param title  标题
     * @param taskId 所属任务；可空
     * @param nodeId 所属节点；可空
     * @param round  ReAct 轮次；可空，为空时沿用上级步骤的轮次
     * @return 新步骤与因此自动完成的同环节步骤（先旧后新）
     */
    public List<ProgressStep> start(StepStage stage, String title, @Nullable String taskId,
                                    @Nullable String nodeId, @Nullable Integer round) {
        List<ProgressStep> changed = new ArrayList<>();
        open.stream().filter(step -> step.getStage() == stage).findFirst().ifPresent(previous ->
            changed.addAll(finish(previous.getStepId(), StepStatus.DONE, previous.getDetail(), null, previous.getExtra()))
        );

        ProgressStep parent = open.peek();
        ProgressStep step = new ProgressStep();
        step.setStepId(RuntimeUtils.uuid());
        step.setParentStepId(Objects.isNull(parent) ? null : parent.getStepId());
        step.setSeq(steps.size() + 1);
        step.setStage(stage);
        step.setTitle(title);
        step.setTaskId(taskId);
        step.setNodeId(nodeId);
        // 轮内的模型、工具步骤未给轮次时沿用所在轮次
        step.setRound(Objects.nonNull(round) || Objects.isNull(parent) ? round : parent.getRound());
        step.setStartedAt(System.currentTimeMillis());
        steps.put(step.getStepId(), step);
        open.push(step);
        changed.add(step);
        return changed;
    }

    /**
     * 结束一个步骤；其下仍在进行的子步骤按同一状态一并结束。
     *
     * @param stepId    步骤标识
     * @param status    结束状态
     * @param detail    补充说明；可空
     * @param errorCode 失败错误码；可空
     * @param extra     排查信息；可空
     * @return 本次结束的步骤（先子后父）；步骤不存在或已结束时为空
     */
    public List<ProgressStep> finish(String stepId, StepStatus status, @Nullable String detail,
                                     @Nullable String errorCode, @Nullable Map<String, Object> extra) {
        ProgressStep target = steps.get(stepId);
        if (Objects.isNull(target) || target.getStatus() != StepStatus.RUNNING) {
            return List.of();
        }

        List<ProgressStep> changed = new ArrayList<>();
        long now = System.currentTimeMillis();
        while (!open.isEmpty()) {
            ProgressStep step = open.pop();
            boolean self = step == target;
            step.setStatus(status);
            step.setElapsedMs(Math.toIntExact(Math.max(0, now - step.getStartedAt())));
            // 连带结束的子步骤与目标同为失败时一并带上错误码
            step.setErrorCode(status == StepStatus.FAILED ? errorCode : null);
            if (self) {
                step.setDetail(detail);
                step.setExtra(extra);
            }
            changed.add(step);
            if (self) {
                break;
            }
        }
        return changed;
    }

    /**
     * 结束全部进行中的步骤。
     *
     * @param status    结束状态
     * @param errorCode 失败错误码；可空
     * @return 本次结束的步骤（先内后外）
     */
    public List<ProgressStep> finishAll(StepStatus status, @Nullable String errorCode) {
        ProgressStep outermost = open.peekLast();
        if (Objects.isNull(outermost)) {
            return List.of();
        }
        return finish(outermost.getStepId(), status, outermost.getDetail(), errorCode, outermost.getExtra());
    }

    /**
     * @param stepId 步骤标识
     * @return 步骤；不存在为空
     */
    public @Nullable ProgressStep find(String stepId) {
        return steps.get(stepId);
    }

    /**
     * @return 待落库的步骤；已落库时为空
     */
    public List<ProgressStep> pending() {
        return persisted ? List.of() : List.copyOf(steps.values());
    }

    /**
     * 落库成功后调用，避免重复写入。
     */
    public void markPersisted() {
        persisted = true;
    }
}
