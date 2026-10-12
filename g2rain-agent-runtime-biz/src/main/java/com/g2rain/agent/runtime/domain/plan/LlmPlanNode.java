package com.g2rain.agent.runtime.domain.plan;


import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.stream.Stream;

/**
 * LlmPlanNode：模型规划结果里的单个步骤节点（反序列化形状）。
 * <p>
 * 必填：nodeId、title、objective、skillKey；dependsOn、target 可空。
 * 只承载模型输出的原始规划数据；进入执行图前仍须字段校验、默认值补齐和依赖合法性检查。
 * </p>
 *
 * @param nodeId    步骤节点 ID
 * @param title     步骤标题
 * @param objective 步骤目标说明
 * @param skillKey  绑定技能键
 * @param dependsOn 依赖的前置步骤 ID 列表；可空
 * @param target    本节点要完成的写工具注册名；纯查询节点为空
 * @author alpha
 * @since 2026/8/21
 */
public record LlmPlanNode(@Nullable String nodeId, @Nullable String title, @Nullable String objective,
                          @Nullable String skillKey, @Nullable List<String> dependsOn, @Nullable String target) {

    /**
     * 关键字段是否有空白：有则视为不完整，整份计划作废。
     *
     * @return 任一关键字段空白则为 true
     */
    public boolean incomplete() {
        return Stream.of(nodeId, title, objective, skillKey).anyMatch(Strings::isBlank);
    }
}
