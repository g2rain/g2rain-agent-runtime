package com.g2rain.agent.runtime.domain.graph;


import java.util.List;

/**
 * 图调度器决定的一种互斥动作。
 * <p>
 * 密封接口：每种实现对应一种调度分支，互斥。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public sealed interface GraphStep {

    /**
     * 按恢复动作直接收口挂起（如 REJECT / CANCEL），不再唤醒焦点节点。
     */
    record CloseByAction() implements GraphStep {

    }

    /**
     * 唤醒当前焦点上的挂起节点，准备继续执行。
     */
    record WakeFocus() implements GraphStep {

    }

    /**
     * 图上暂时没有可运行节点时执行空闲处理，例如判断终态或推进委派等待状态。
     */
    record ResolveIdle() implements GraphStep {

    }

    /**
     * 执行本波可调度节点。
     *
     * @param wave 本波要执行的节点列表；构造时会拷贝为不可变副本
     */
    record ExecuteWave(List<TaskNode> wave) implements GraphStep {
        public ExecuteWave {
            wave = List.copyOf(wave);
        }
    }
}
