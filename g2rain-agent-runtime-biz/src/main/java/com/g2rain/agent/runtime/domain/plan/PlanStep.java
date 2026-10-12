package com.g2rain.agent.runtime.domain.plan;


import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * PlanStep（计划步骤）：规划产出中的一个可执行节点描述。
 * <p>
 * 把「做什么、用哪个技能、依赖哪些前置节点」固化为一行步骤，便于构图与调度。
 * 例：{@code nodeId=n2, skillKey=refund, dependsOn=[n1]} 表示退款步骤依赖 n1 完成。
 * 该对象是规划结果中的稳定步骤描述，供任务图构建器转换为可执行节点；它不保存运行时状态。
 * </p>
 *
 * @param nodeId    节点唯一标识
 * @param title     步骤标题（短名）
 * @param objective 步骤目标说明
 * @param skillKey  绑定的技能键
 * @param dependsOn 前置节点 id 列表；空表示无依赖
 * @param target    本节点要完成的写工具注册名；可空
 * @author alpha
 * @since 2026/8/21
 */
public record PlanStep(String nodeId, String title, String objective, String skillKey, List<String> dependsOn,
                       @Nullable String target) {

    public PlanStep {
        // 依赖列表固化为不可变副本
        dependsOn = List.copyOf(dependsOn);
    }

    /**
     * 从 LLM 规划节点 DTO 转为领域步骤；空依赖按空列表处理。
     *
     * @param node LLM 规划节点
     * @return 领域计划步骤
     */
    public static PlanStep from(LlmPlanNode node) {
        // 依赖缺省为空列表；其余字段要求非空再落入领域步骤
        List<String> dependsOn = Objects.requireNonNullElse(node.dependsOn(), List.of());
        String nodeId = Objects.requireNonNull(node.nodeId());
        String title = Objects.requireNonNull(node.title());
        String objective = Objects.requireNonNull(node.objective());
        String skillKey = Objects.requireNonNull(node.skillKey());
        return new PlanStep(nodeId, title, objective, skillKey, dependsOn, Strings.blankToNull(node.target()));
    }

    /**
     * 换完成目标。
     *
     * @param target 写工具注册名；可空
     * @return 新步骤
     */
    public PlanStep withTarget(@Nullable String target) {
        return new PlanStep(nodeId, title, objective, skillKey, dependsOn, target);
    }
}
