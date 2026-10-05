package com.g2rain.agent.runtime.engine.flow.graph;


import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.TaskNode;

import java.util.List;

/**
 * NodeRunner（节点执行器）：跑单个任务节点，产出对本拍有效的图副作用。
 * <p>
 * 典型路径：节点进 RUNNING →（可选）派发待批 → 进入节点内循环 → 按结果发事件/挂起/完成。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface NodeRunner {

    /**
     * 执行指定节点。
     *
     * @param node  要跑的节点
     * @param frame 当前图执行帧
     * @return 副作用列表（给 {@link EffectHandler} 应用）
     */
    List<GraphEffect> run(TaskNode node, GraphFrame frame);
}
