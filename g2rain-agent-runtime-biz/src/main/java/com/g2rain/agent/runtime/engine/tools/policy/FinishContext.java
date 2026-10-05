package com.g2rain.agent.runtime.engine.tools.policy;


import com.g2rain.agent.runtime.domain.hydrate.DoneWhenExpr;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * 评估 finish 门槛时的只读入参。
 *
 * <p>节点与契约一律从 {@link #execution} 派生，避免与 ToolExecutionContext 双份传递。</p>
 *
 * @param output    拟写入的节点产出；可空
 * @param execution 工具执行上下文；可空（无上下文时仅能做极简判定）
 * @author alpha
 * @since 2026/8/21
 */
public record FinishContext(@Nullable Object output, @Nullable ToolExecutionContext execution) {

    /**
     * @return 当前节点视图；可空
     */
    public @Nullable NodeScope node() {
        return Objects.isNull(execution) ? null : execution.scope();
    }

    /**
     * @return 当前节点 ID；可空
     */
    public @Nullable String nodeId() {
        return Objects.isNull(execution) ? null : execution.getNodeId();
    }

    /**
     * @return 契约目标业务工具名；可空
     */
    public @Nullable String targetToolName() {
        return Objects.isNull(execution) ? null : Strings.blankToNull(execution.getTargetToolName());
    }

    /**
     * @return 装载期解析的 doneWhen 树；可空
     */
    public @Nullable DoneWhenExpr doneWhenExpr() {
        SkillContract contract = contract();
        return Objects.isNull(contract) ? null : contract.getDoneWhenExpr();
    }

    /**
     * @return 是否期望至少成功过业务工具
     */
    public boolean expectsBusinessTools() {
        return Objects.nonNull(execution) && execution.expectsBusinessTools();
    }

    /**
     * @return 当前技能契约；可空
     */
    public @Nullable SkillContract contract() {
        return Objects.isNull(execution) ? null : execution.contract();
    }
}
