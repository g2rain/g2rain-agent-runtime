package com.g2rain.agent.runtime.domain.state;


/**
 * 值来源的分类。
 *
 * <p>类型用于约束参数绑定的可信边界：例如用户值必须能回指消息，证据值必须能回指工具
 * 观测，常量和平台值也必须保留具体引用。表达式 {@code shared.*} 对应 {@link #SHARED}，
 * {@code session.*} 对应 {@link #SESSION}。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public enum SourceType {

    /**
     * 用户显式提供。
     * <p>当前对话或追问回复里用户给出的值。</p>
     */
    USER,

    /**
     * 工具证据。
     * <p>ref 为 evidenceId，path 在证据报文中。</p>
     */
    EVIDENCE,

    /**
     * 上游节点 outputs。
     * <p>ref 为任务图节点的唯一标识。</p>
     */
    NODE_OUTPUT,

    /**
     * 任务级共享值。
     * <p>表达式 {@code shared.*}。</p>
     */
    SHARED,

    /**
     * 会话级共享值。
     * <p>表达式 {@code session.*}；任务结束不删除。</p>
     */
    SESSION,

    /**
     * 任务前情。
     * <p>来自上一 Task 留下的衔接包，例如 Prior 中的回合摘要或实体。</p>
     */
    PRIOR_TASK,

    /**
     * 大模型生成。
     * <p>如备注、标题；仅契约 sources 包含 MODEL 时可用。</p>
     */
    MODEL,

    /**
     * 契约常量。
     * <p>const；必须记录具体 ref/path。</p>
     */
    CONTRACT,

    /**
     * 平台注入。
     * <p>如 organId / userId；必须记录具体 ref/path。</p>
     */
    PLATFORM
}
