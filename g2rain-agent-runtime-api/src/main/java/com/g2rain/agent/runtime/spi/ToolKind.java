package com.g2rain.agent.runtime.spi;

/**
 * 工具种类：按「做什么」给工具归类，供执行步骤展示图标。
 *
 * <p>内置工具按 {@code BuiltinToolCategory} 对应；能力工具在 {@link CapabilitySpec#kind()} 中声明；
 * 业务 MCP 工具统一为 {@link #BUSINESS}。本枚举不影响工具的调度与校验。</p>
 *
 * <pre>{@code
 * new CapabilitySpec(NAME, LABEL, DESCRIPTION, inputSchema, true, ToolKind.SEARCH);
 * }</pre>
 *
 * @author alpha
 * @since 2026/11/27
 */
public enum ToolKind {

    /**
     * 控制流：追问、审批、委派、完成或失败收束。
     */
    CONTROL,

    /**
     * 环境：当前时间、UUID、日期计算等通用查询。
     */
    ENV,

    /**
     * 会话：读取会话或节点侧上下文。
     */
    SESSION,

    /**
     * 节点：读写节点产出、便签与证据。
     */
    NODE,

    /**
     * 联网搜索。
     */
    SEARCH,

    /**
     * 读取网页正文。
     */
    FETCH,

    /**
     * 把查询结果导入任务分析库。
     */
    IMPORT,

    /**
     * 对任务分析库执行分析查询。
     */
    ANALYZE,

    /**
     * 生成报告。
     */
    REPORT,

    /**
     * 业务 MCP 工具。
     */
    BUSINESS
}
