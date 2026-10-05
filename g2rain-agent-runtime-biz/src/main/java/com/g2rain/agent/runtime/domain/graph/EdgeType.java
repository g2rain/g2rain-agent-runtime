package com.g2rain.agent.runtime.domain.graph;

/**
 * 任务图边的语义类型。
 *
 * <p>边类型只描述节点之间的协作关系，不决定节点的执行结果。调度器据此区分普通前置依赖
 * 与需要等待委派结果的协作关系。</p>
 *
 * @author alpha
 * @since 2026/9/10
 */
public enum EdgeType {

    /**
     * 普通依赖边；下游节点等待上游节点完成后才能继续。
     */
    NORMAL,

    /**
     * 委派边；表示当前节点由另一个节点负责前置处理或协作完成。
     */
    DELEGATE
}
