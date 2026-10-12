package com.g2rain.agent.runtime.engine.flow.graph;


import com.g2rain.agent.runtime.domain.execute.ExecutorResult;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.GraphStep;
import com.g2rain.agent.runtime.domain.graph.NodeEvent;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * DefaultGraphTransitionReducer：默认图步骤归约器。
 * <p>
 * 按步骤分支：关挂起 → 唤醒焦点 → 空闲停图 → 执行波（委托 {@link NodeRunner}）。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultGraphTransitionReducer implements GraphTransitionReducer {

    /**
     * 审批摘要末尾的标点，拼进拒绝回执前去掉。
     */
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[。.！!；;，,]+$");

    /**
     * 节点执行器：执行波时跑波内首个节点。
     */
    private final DefaultNodeRunner nodeRunner;

    /**
     * 节点状态机：关挂起时推进节点态。
     */
    private final NodeStateMachine nodeStateMachine;

    /**
     * 把本拍步骤归约成副作用列表。
     *
     * @param frame 当前图执行帧
     * @param step  本拍步骤
     * @return 待应用的副作用
     */
    @Override
    public List<GraphEffect> reduce(GraphFrame frame, GraphStep step) {
        List<GraphEffect> effects = switch (step) {
            // 用户拒绝/取消：关挂起并停图
            case GraphStep.CloseByAction _ -> closeByAction(frame);
            // 续跑唤醒：挂起节点改为 READY，本拍无副作用
            case GraphStep.WakeFocus _ -> wakeFocus(frame);
            // 无可跑节点：按图状态产出最终停图回执
            case GraphStep.ResolveIdle _ -> resolveIdle(frame);
            // 有可跑波：委托节点执行器（当前只跑首个）
            case GraphStep.ExecuteWave ew -> executeWave(ew.wave(), frame);
        };

        log.debug("[GraphReducer] reduce taskId={}, step={}, effects={}", frame.taskId(), step, effects);
        return effects;
    }

    /**
     * 按用户动作关闭挂起焦点：取消与拒绝都把节点置为 CANCELLED、任务置为 CANCELED。
     *
     * <p>拒绝时回执带上未执行的操作（审批摘要），并提示用户可以换个做法再说。</p>
     *
     * @param frame 当前图执行帧
     * @return 停图副作用；找不到挂起点时退回空闲收口
     */
    private List<GraphEffect> closeByAction(GraphFrame frame) {
        // 按焦点找挂起节点；焦点空则取图上第一个挂起节点
        TaskNode node = frame.graph().focusSuspended(frame.currentNodeId());
        if (Objects.isNull(node)) {
            // 没有可关的挂起点：按当前图态收口
            return resolveIdle(frame);
        }

        // CANCEL → 取消；其余关挂起动作（典型拒绝审批）→ 拒绝；两者都按取消收口
        boolean rejected = !GraphResumeActions.isCancel(frame.action());
        NodeEvent event = rejected ? NodeEvent.USER_REJECTED : NodeEvent.USER_CANCELLED;
        NodeTransition transition = nodeStateMachine.transit(event, node.getStatus());
        frame.turn().mark(node.getNodeId(), transition.to(), null, null);

        String message = rejected ? rejectedMessage(frame) : null;
        return List.of(
            new GraphEffect.ClearSuspend(node.getNodeId()),
            new GraphEffect.Halt(new ExecutorResult(TaskStatus.CANCELED, node.getNodeId(), message, null, List.of()))
        );
    }

    /**
     * 拒绝审批的回执：说明未执行的操作（审批摘要），并提示用户可以换个做法。
     *
     * @param frame 当前图执行帧
     * @return 回执文案；不是审批断点或没有摘要时用通用取消文案
     */
    private static String rejectedMessage(GraphFrame frame) {
        String action = frame.turn().checkpoint().or(() -> frame.turn().resumed())
            .filter(checkpoint -> checkpoint.getType() == CheckpointType.APPROVAL)
            .map(TaskCheckpoint::getQuestion)
            .filter(Strings::isNotBlank)
            .map(question -> TRAILING_PUNCTUATION.matcher(question.trim()).replaceAll(""))
            .filter(Strings::isNotBlank)
            .orElse(null);
        return Objects.isNull(action) ? RuntimeMessage.REPLY_CANCELLED.text() : RuntimeMessage.REPLY_REJECTED.text("action", action);
    }

    /**
     * 唤醒焦点挂起节点为 READY，并消费断点：用户已在本轮回应，断点不再是"等待中"。
     *
     * <p>消费后的断点仍可经 {@code turn.resumed()} 读取，确认续跑时据此派发待批写操作。</p>
     *
     * @param frame 当前图执行帧
     * @return 清挂起副作用；没有挂起焦点时为空
     */
    private List<GraphEffect> wakeFocus(GraphFrame frame) {
        TaskNode focus = frame.graph().peekSuspendedFocus(frame.currentNodeId());
        frame.graph().readySuspendedFocus(frame.currentNodeId());
        if (Objects.isNull(focus)) {
            return List.of();
        }

        return List.of(new GraphEffect.ClearSuspend(focus.getNodeId()));
    }

    /**
     * 无可跑波时按图现状停图。
     *
     * @param frame 当前图执行帧
     * @return 仅含 Halt 的副作用
     */
    private List<GraphEffect> resolveIdle(GraphFrame frame) {
        return List.of(new GraphEffect.Halt(whenIdle(frame)));
    }

    /**
     * 执行本波：当前只跑波内第一个节点。
     *
     * @param wave  本波节点
     * @param frame 当前图执行帧
     * @return 节点执行产出的副作用
     */
    private List<GraphEffect> executeWave(List<TaskNode> wave, GraphFrame frame) {
        return nodeRunner.run(wave.getFirst(), frame);
    }

    /**
     * 空闲收口：按挂起 → 失败类终态 → 在途死锁 → 全完成，产出任务级回执。
     *
     * @param frame 当前图执行帧
     * @return 停图回执
     */
    private static ExecutorResult whenIdle(GraphFrame frame) {
        TaskGraph graph = frame.graph();
        // 空图直接当成功收口
        if (Collections.isEmpty(graph.getNodes())) {
            return new ExecutorResult(TaskStatus.SUCCEEDED);
        }

        // 优先：挂起等人（续跑令牌落在该节点）
        for (TaskNode node : graph.getNodes()) {
            if (node.getStatus() == NodeStatus.SUSPENDED) {
                return frame.turn().checkpoint()
                    .map(checkpoint -> suspendedResult(node, checkpoint))
                    .orElseGet(() -> new ExecutorResult(TaskStatus.SUSPENDED, node.getNodeId()));
            }
        }

        // 其次：节点已失败类终态（DELEGATE 子节点失败交还给发起节点，不关任务）
        for (TaskNode node : graph.getNodes()) {
            NodeStatus status = node.getStatus();
            if (status == NodeStatus.FAILED) {
                if (delegateChild(graph, node)) {
                    continue;
                }

                return new ExecutorResult(TaskStatus.FAILED, node.getNodeId());
            }

            if (status == NodeStatus.CANCELLED) {
                if (delegateChild(graph, node)) {
                    continue;
                }

                return new ExecutorResult(TaskStatus.CANCELED, node.getNodeId());
            }
        }

        // 再次：还有在途节点却调度不出下一波 → 依赖未齐
        for (TaskNode node : graph.getNodes()) {
            NodeStatus status = node.getStatus();
            if (status.isActive()) {
                return new ExecutorResult(
                    TaskStatus.FAILED, node.getNodeId(),
                    RuntimeMessage.ERROR_GENERIC.text()
                );
            }
        }

        // 全部 DONE/SKIPPED 等可收口态
        return new ExecutorResult(TaskStatus.SUCCEEDED);
    }

    /**
     * 把任务检查点转成挂起回执；检查点丢失时给可续跑的最小默认。
     *
     * @param node       已挂起的节点
     * @param checkpoint 任务检查点；可空
     * @return 挂起回执
     */
    private static ExecutorResult suspendedResult(TaskNode node, TaskCheckpoint checkpoint) {
        return new ExecutorResult(
            TaskStatus.SUSPENDED,
            node.getNodeId(),
            checkpoint.getQuestion(),
            checkpoint.getType(),
            List.copyOf(checkpoint.getAllowedActions())
        );
    }

    /**
     * DELEGATE 边的前置（子）节点：失败只写 {@code delegateResult}，不把整图打成 FAILED。
     *
     * @param graph 当前任务图
     * @param node  待判断节点
     * @return 该节点是委派子节点时为 {@code true}
     */
    private static boolean delegateChild(TaskGraph graph, TaskNode node) {
        return graph.getEdges().stream().anyMatch(edge ->
            edge.isDelegate() && node.getNodeId().equals(edge.getFrom())
        );
    }
}
