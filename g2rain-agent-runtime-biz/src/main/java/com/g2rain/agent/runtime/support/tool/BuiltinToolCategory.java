package com.g2rain.agent.runtime.support.tool;


/**
 * 内置工具类别：按职责把内置工具分成几大类。
 *
 * <p>写在 {@link BuiltinToolKind#category()} 上，供回调装饰与上层按类筛选。本枚举不执行工具。</p>
 *
 * @author alpha
 * @since 2026/8/29
 */
public enum BuiltinToolCategory {

    /**
     * 控制流工具
     * <p>
     * 驱动节点走向：追问用户、人工批准、委派子技能、正常完成或失败收束。
     */
    CONTROL,

    /**
     * 环境工具
     * <p>
     * 与业务状态无关的通用能力：当前时间、UUID、日期加减、哈希等查询。
     */
    ENV,

    /**
     * 会话工具
     * <p>
     * 读取当前会话/节点侧上下文信息，例 conversationId、节点状态。
     */
    SESSION,

    /**
     * 节点输出、便签与按需读证据。
     * <p>
     * 读写节点侧任务记忆：写产出、读证据摘要等。
     */
    NODE
}
