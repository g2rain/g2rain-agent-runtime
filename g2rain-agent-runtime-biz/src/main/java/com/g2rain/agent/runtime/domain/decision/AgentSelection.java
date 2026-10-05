package com.g2rain.agent.runtime.domain.decision;


import com.g2rain.agent.studio.vo.AgentCatalogItemVo;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Agent 路由阶段使用的候选画像。
 *
 * <p>该对象只保留候选的身份和简短描述，不携带完整 Agent 配置、工具列表或运行状态。
 * 调用方可以先用它完成候选筛选，再根据 {@link #agentId} 加载真正的运行配置。
 * 例如，目录中的“退款助手”会被转换为一条包含名称和使命描述的候选记录。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Getter
@Setter
@NoArgsConstructor
public final class AgentSelection {

    /**
     * Agent 在目录中的唯一标识。
     *
     * <p>值为空表示该候选没有可用于回查配置的目录标识；这种候选只能作为展示或文本匹配结果，
     * 不能直接用于启动运行任务。</p>
     */
    private @Nullable Long agentId;

    /**
     * Agent 面向用户展示的名称。
     *
     * <p>名称用于候选展示和文本匹配，不承担唯一标识职责；唯一性由 {@link #agentId} 保证。</p>
     */
    private @Nullable String agentName;

    /**
     * 用于判断候选是否适合当前请求的简介文本。
     *
     * <p>从目录转换时优先使用使命描述，在使命描述为空时回退到身份描述；两者都为空时保持
     * {@code null}，避免用默认文案制造不存在的能力描述。</p>
     */
    private @Nullable String profileText;

    /**
     * 根据已提取的候选字段创建候选画像。
     *
     * <p>方法不做目录查询、不补默认值，也不修改传入字符串；调用方负责保证字段来自可信目录
     * 或已经完成校验的路由结果。</p>
     *
     * @param agentId     主键；可空
     * @param agentName   名称；可空
     * @param profileText 简介；可空
     * @return 新候选实例
     */
    public static AgentSelection of(@Nullable Long agentId, @Nullable String agentName, @Nullable String profileText) {
        AgentSelection selection = new AgentSelection();
        selection.setAgentId(agentId);
        selection.setAgentName(agentName);
        selection.setProfileText(profileText);
        return selection;
    }

    /**
     * 将 Agent 目录条目批量转换为路由候选。
     *
     * <p>每个条目只映射身份字段和简介字段，简介由 {@link #descriptionOf(AgentCatalogItemVo)}
     * 按优先级提取。空集合或 {@code null} 入参都返回不可变的空列表。</p>
     *
     * @param items Agent 目录条目
     * @return 候选列表（不可变元素序列）
     */
    public static List<AgentSelection> of(List<AgentCatalogItemVo> items) {
        // 没有目录候选时直接返回不可变空列表，避免后续流处理额外分支。
        if (Collections.isEmpty(items)) {
            return List.of();
        }

        // 只投影路由所需字段；目录对象的其他配置不会泄漏到候选画像中。
        return items.stream().map(item -> AgentSelection.of(
            item.getAgentId(),
            item.getAgentName(), descriptionOf(item)
        )).toList();
    }

    /**
     * 从目录条目选择用于路由匹配的简介。
     *
     * <p>使命描述通常比身份描述更具体，因此优先保留使命描述；当它为空时才使用身份描述。
     * 返回值会去除首尾空白，但不会改写正文内容。</p>
     *
     * @param item 目录条目
     * @return 简介文本；皆空则为 {@code null}
     */
    private static @Nullable String descriptionOf(AgentCatalogItemVo item) {
        // 使命描述表达可承担的工作范围，优先作为候选画像。
        if (Strings.isNotBlank(item.getMissionDesc())) {
            return item.getMissionDesc().trim();
        }

        // 没有使命描述时，退回到身份描述维持候选可识别性。
        if (Strings.isNotBlank(item.getIdentityDesc())) {
            return item.getIdentityDesc().trim();
        }

        // 目录没有可用描述时保持缺失，而不是伪造一段说明。
        return null;
    }
}
