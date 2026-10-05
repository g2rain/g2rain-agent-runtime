package com.g2rain.agent.runtime.engine.flow.graph;


import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.GraphStep;

/**
 * GraphKernel（图内核）：根据当前执行帧，决定本拍图上要做的下一步。
 * <p>
 * 只产出 {@link GraphStep}（唤醒、关挂起、跑波、空闲收口等），不直接改写任务记忆或会话记忆。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface GraphKernel {

    /**
     * 计算本拍下一步。
     *
     * @param frame 当前图执行帧
     * @return 下一步步骤描述
     */
    GraphStep next(GraphFrame frame);
}
