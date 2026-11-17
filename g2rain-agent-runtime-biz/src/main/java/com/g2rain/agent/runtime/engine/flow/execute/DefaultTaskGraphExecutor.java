package com.g2rain.agent.runtime.engine.flow.execute;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.execute.ExecutorResult;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.GraphStep;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.flow.graph.EffectHandler;
import com.g2rain.agent.runtime.engine.flow.graph.GraphKernel;
import com.g2rain.agent.runtime.engine.flow.graph.GraphTransitionReducer;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 默认任务图执行器：在 {@link TurnScope} 上循环「取下一步 → 归约 → 应用副作用」直到停图。
 *
 * <p>节点态随 flush/persist 落盘，不整图 {@code saveGraph}。无 Task 时把上下文标 failed。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Component
@RequiredArgsConstructor
public class DefaultTaskGraphExecutor implements TaskGraphExecutor {

    /**
     * 根据当前帧决定本拍 GraphStep。
     */
    private final GraphKernel graphKernel;

    /**
     * 把副作用落到帧、断点与事件上。
     */
    private final EffectHandler effectHandler;

    /**
     * 把 GraphStep 归约成副作用列表，执行波时会进节点 / ReAct。
     */
    private final GraphTransitionReducer graphTransitionReducer;

    /**
     * 运行时配置：任务图调度步数上限。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 执行任务图直至完成、挂起或失败，并把 {@link ExecutorResult} 写入上下文。
     *
     * @param context 本轮上下文（须已有会话与 Task）
     */
    @Override
    public void execute(TurnContext context) {
        TurnScope turn = context.getTurn();
        if (turn.task().isEmpty()) {
            Trace.at(TraceStage.GRAPH, "task_missing").error();
            context.setFailed(true);
            return;
        }

        GraphFrame frame = context.graphFrame();
        int maxSteps = runtimeProperties.getPlan().getMaxGraphSteps();
        int steps = 0;
        while (!frame.terminal() && steps++ < maxSteps) {
            GraphStep step = graphKernel.next(frame);
            var reduce = graphTransitionReducer.reduce(frame, step);
            effectHandler.apply(frame, reduce);
        }

        // 调度来回打转时不能无限跑下去：超出步数上限按失败收口
        if (!frame.terminal()) {
            Trace.at(TraceStage.GRAPH, "step_limit").kv("maxSteps", maxSteps).error();
            frame.halt(new ExecutorResult(TaskStatus.FAILED));
        }

        // 循环只在停图后退出，回执必然存在
        ExecutorResult result = Objects.requireNonNull(frame.halt());

        turn.task().ifPresent(meta -> {
            meta.setStatus(result.status());
            String id = result.currentNodeId();
            if (Strings.isBlank(id)) {
                return;
            }

            turn.focus(id);
        });

        context.setExecutorResult(result);
    }
}
