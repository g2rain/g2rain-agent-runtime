package com.g2rain.agent.runtime.domain.state.task;


/**
 * 节点修复指令要求执行的下一类动作。
 *
 * <p>指令由参数绑定或来源校验产生，模型只能围绕该义务修复当前节点，不能跳过义务直接
 * 重新尝试原工具调用。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public enum RepairDirectiveType {

    /**
     * 须追问用户：缺参或需用户确认，模型应 follow_up。
     */
    ASK_USER_REQUIRED,

    /**
     * 须先调指定工具：Evidence / 上游数据未齐，先调完再继续。
     */
    CALL_TOOL_REQUIRED,

    /**
     * 平台上下文缺失：ambient / Principal 拿不到必备字段，通常应 node_fail。
     */
    PLATFORM_CONTEXT_MISSING,

    /**
     * 溯源被拒：参数来源不可信（如禁止模型编造），须追问用户或改用来源证明。
     */
    PROVENANCE_REJECT
}
