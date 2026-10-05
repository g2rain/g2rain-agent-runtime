package com.g2rain.agent.runtime.domain.hydrate;

/**
 * 输出共享范围。
 *
 * <p>节点写出后按范围沉淀：任务级随任务结束清理，会话级跨任务保留。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public enum ShareScope {

    /**
     * 任务级共享。
     * <p>写入当前任务 shared；任务关闭后不再作为活跃键。</p>
     */
    TASK,

    /**
     * 会话级共享。
     * <p>写入 sessionShared；任务结束不删除，后续 Task 仍可绑定。</p>
     */
    SESSION
}
