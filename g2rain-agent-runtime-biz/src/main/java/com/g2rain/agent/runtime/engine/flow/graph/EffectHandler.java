package com.g2rain.agent.runtime.engine.flow.graph;


import com.g2rain.agent.runtime.domain.graph.GraphFrame;

import java.util.List;

/**
 * EffectHandler（副作用应用）：把归约出的 {@link GraphEffect} 落到执行帧与任务图上。
 * <p>
 * 负责发事件、写/清挂起快照、挂上停图结论等。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface EffectHandler {

    /**
     * 按顺序应用副作用。
     *
     * @param frame   当前图执行帧
     * @param effects 副作用列表；空则直接返回
     */
    void apply(GraphFrame frame, List<GraphEffect> effects);
}
