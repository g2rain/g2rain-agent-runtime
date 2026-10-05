package com.g2rain.agent.runtime.support.tool;


/**
 * 内置工具在其类别下的具体动作。
 *
 * <p>写在 {@link BuiltinToolKind#action()} 上，与 {@link BuiltinToolCategory} 组合标识一种工具语义。
 * 本枚举不执行工具。</p>
 *
 * @author alpha
 * @since 2026/8/29
 */
public enum BuiltinToolAction {

    /**
     * 追问用户
     * <p>
     * 缺参或需澄清时向用户发出追问，节点进入等待回复。
     */
    ASK,

    /**
     * 失败收束
     * <p>
     * 节点/任务判定无法继续时，带原因失败结束。
     */
    FAIL,

    /**
     * 正常完成
     * <p>
     * 节点产出正式结果并成功结束，例提交最终回复文案。
     */
    FINISH,

    /**
     * 人工批准
     * <p>
     * 高风险操作先挂起，等人确认后再执行目标工具。
     */
    APPROVAL,

    /**
     * 委派子技能
     * <p>
     * 把当前目标拆给子技能/子步骤继续执行。
     */
    DELEGATE,

    /**
     * 读取
     * <p>
     * 只读拉取数据：读任务记忆字段，或读会话侧信息。
     */
    READ,

    /**
     * 写入
     * <p>
     * 变更任务记忆：set / merge / clear 等写路径。
     */
    WRITE,

    /**
     * 查询
     * <p>
     * 无状态环境查询：时间、UUID、日期差、随机数、哈希等。
     */
    QUERY
}
