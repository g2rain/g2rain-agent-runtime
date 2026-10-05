package com.g2rain.agent.runtime.engine.flow.graph;


import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.GraphStep;

import java.util.List;

/**
 * GraphTransitionReducer（图步骤归约）：把 {@link GraphStep} 变成可应用的 {@link GraphEffect} 列表。
 * <p>
 * 例如 ExecuteWave 会进入节点执行（含 ReAct），产出发事件、挂起、停图等副作用。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface GraphTransitionReducer {

    /**
     * 归约一步为副作用列表。
     *
     * @param frame 当前图执行帧
     * @param step  本拍步骤
     * @return 待应用的副作用；可为空列表
     */
    List<GraphEffect> reduce(GraphFrame frame, GraphStep step);
}
