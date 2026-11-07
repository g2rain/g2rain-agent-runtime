package com.g2rain.agent.runtime.support.tool;

import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import org.jspecify.annotations.Nullable;


/**
 * 内置工具在其类别下的具体动作。
 *
 * <p>写在 {@link BuiltinToolKind#action()} 上，与 {@link BuiltinToolCategory} 组合标识一种工具语义。
 * 本枚举不执行工具；{@link #step()} 声明该动作是否作为执行步骤展示给客户端。</p>
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
    ASK(RuntimeMessage.STEP_ASK),

    /**
     * 失败收束
     * <p>
     * 节点/任务判定无法继续时，带原因失败结束。
     */
    FAIL(RuntimeMessage.STEP_FAIL),

    /**
     * 正常完成
     * <p>
     * 节点产出正式结果并成功结束，例提交最终回复文案。
     */
    FINISH(RuntimeMessage.STEP_FINISH),

    /**
     * 跳过
     * <p>
     * 查询结果表明本节点的写操作不需要执行，节点以 SKIPPED 结束。
     */
    SKIP(RuntimeMessage.STEP_SKIP),

    /**
     * 人工批准
     * <p>
     * 高风险操作先挂起，等人确认后再执行目标工具。
     */
    APPROVAL(RuntimeMessage.STEP_APPROVAL),

    /**
     * 委派子技能
     * <p>
     * 把当前目标拆给子技能/子步骤继续执行。
     */
    DELEGATE(RuntimeMessage.STEP_DELEGATE),

    /**
     * 读取
     * <p>
     * 只读拉取数据：读任务记忆字段，或读会话侧信息。
     */
    READ(null),

    /**
     * 写入
     * <p>
     * 变更任务记忆：set / merge / clear 等写路径。
     */
    WRITE(null),

    /**
     * 写产出
     * <p>
     * 写入节点正式产出，供完成校验、共享与实体沉淀使用。
     */
    OUTPUT(null),

    /**
     * 查询
     * <p>
     * 无状态环境查询：时间、UUID、日期差、随机数、哈希等。
     */
    QUERY(null);

    /**
     * 作为执行步骤展示时的标题；为空表示不展示（内部记账类动作）。
     */
    private final @Nullable RuntimeMessage step;

    /**
     * @param step 步骤标题；为空表示不展示
     */
    BuiltinToolAction(@Nullable RuntimeMessage step) {
        this.step = step;
    }

    /**
     * @return 步骤标题；为空表示不作为执行步骤展示
     */
    public @Nullable RuntimeMessage step() {
        return step;
    }
}
