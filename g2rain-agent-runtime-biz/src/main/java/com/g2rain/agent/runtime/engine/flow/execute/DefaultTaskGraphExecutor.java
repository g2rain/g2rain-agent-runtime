package com.g2rain.agent.runtime.engine.flow.execute;


import com.g2rain.agent.runtime.domain.execute.ExecutorResult;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.GraphStep;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.flow.graph.EffectHandler;
import com.g2rain.agent.runtime.engine.flow.graph.GraphKernel;
import com.g2rain.agent.runtime.engine.flow.graph.GraphTransitionReducer;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
     * 执行任务图直至完成、挂起或失败，并把 {@link ExecutorResult} 写入上下文。
     *
     * @param context 本轮上下文（须已有会话与 Task）
     */
    @Override
    public void execute(TurnContext context) {
        TurnScope turn = context.getTurn();
        if (turn.task().isEmpty()) {
            log.info("[TaskGraphExecutor] fail missing task");
            context.setFailed(true);
            return;
        }

        GraphFrame frame = context.graphFrame();
        while (!frame.terminal()) {
            GraphStep step = graphKernel.next(frame);
            var reduce = graphTransitionReducer.reduce(frame, step);
            effectHandler.apply(frame, reduce);
        }

        ExecutorResult halt = frame.halt();
        // 停图副作用未挂回执时按成功收口，避免空结果阻断 finalize
        ExecutorResult result;
        if (Objects.nonNull(halt)) {
            result = halt;
        } else {
            result = new ExecutorResult(TaskStatus.SUCCEEDED);
        }

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
