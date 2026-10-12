package com.g2rain.agent.runtime.engine.flow.graph;


import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.GraphStep;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceEvent;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.utils.Strings;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 默认图内核：根据当前执行帧决定本拍要做的下一步。
 *
 * <p>顺序：先处理本帧唤醒或关挂起（每帧一次），再取可跑波；无波则空闲收口。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Component
public class DefaultGraphKernel implements GraphKernel {

    /**
     * 计算本拍下一步并输出 {@code [Trace][GRAPH] next} 轨迹。
     *
     * @param frame 当前图执行帧
     * @return 本拍步骤
     */
    @Override
    public GraphStep next(GraphFrame frame) {
        GraphStep step = decide(frame);
        trace(step, frame);
        return step;
    }

    /**
     * 计算本拍下一步：唤醒分支只处理一次，随后按依赖取波或空闲收口。
     *
     * @param frame 当前图执行帧
     * @return 本拍步骤
     */
    private static GraphStep decide(GraphFrame frame) {
        // 唤醒/关挂起每帧只走一次，避免主循环里反复处理
        if (!frame.wakeDone()) {
            frame.markWakeDone();
            ResumeAction action = frame.action();
            boolean verifyCheckpoint = frame.turn().checkpoint()
                .map(checkpoint -> checkpoint.getType() == CheckpointType.VERIFY)
                .orElse(false);

            // 用户拒绝或取消：不进节点，直接关挂起（VERIFY 的 REJECT 只改证据，不关任务）
            if (GraphResumeActions.closesSuspend(action) && !verifyCheckpoint) {
                return new GraphStep.CloseByAction();
            }

            // 有挂起焦点才谈唤醒；首轮无挂起则跳过
            TaskNode suspended = frame.graph().peekSuspendedFocus(frame.currentNodeId());

            if (Objects.nonNull(suspended)) {
                // 确认审批，或本轮带了文字：先唤醒挂起焦点
                String userInput = frame.userInput();
                if (GraphResumeActions.wakesFocus(action)
                    || (verifyCheckpoint && action == ResumeAction.REJECT)
                    || Strings.isNotBlank(userInput)) {
                    return new GraphStep.WakeFocus();
                }
            }
        }

        // 按依赖把可跑节点推进到 READY，并取本波（当前按单节点调度）
        List<TaskNode> wave = frame.graph().nextWave();
        if (wave.isEmpty()) {
            // 没有可跑节点：交给空闲收口（挂起/失败/成功等）
            return new GraphStep.ResolveIdle();
        }

        // 有可跑波：本拍执行（归约侧只跑波内第一个节点）
        return new GraphStep.ExecuteWave(wave);
    }

    /**
     * 输出本拍步骤轨迹：步骤名取自 {@link GraphStep} 的实现类型，并附带该步骤相关的字段。
     *
     * @param step  本拍步骤
     * @param frame 当前图执行帧
     */
    private static void trace(GraphStep step, GraphFrame frame) {
        TraceEvent event = Trace.at(TraceStage.GRAPH, "next").kv("step", step.getClass().getSimpleName());
        switch (step) {
            case GraphStep.ExecuteWave wave -> event.kv("nodeIds", wave.wave().stream().map(TaskNode::getNodeId).toList());
            case GraphStep.WakeFocus _ -> event
                .kv("nodeId", frame.currentNodeId())
                .kv("action", frame.action())
                .kv("hasInput", Strings.isNotBlank(frame.userInput()));
            case GraphStep.CloseByAction _ -> event.kv("action", frame.action());
            case GraphStep.ResolveIdle _ -> {
            }
        }

        event.info();
    }
}
