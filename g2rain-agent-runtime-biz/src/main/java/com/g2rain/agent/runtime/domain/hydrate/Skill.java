package com.g2rain.agent.runtime.domain.hydrate;


import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Skill（技能）：本轮装载后的一条可执行能力单元。
 * <p>
 * 用唯一键标识，可带语义短描述（给筛选/规划看）、业务工具名列表、操作手册（给执行看），以及技能契约。
 * </p>
 *
 * @param skillKey     技能唯一键
 * @param toolNames    该技能可调用的业务工具名列表；空表示无业务工具
 * @param semanticDesc 语义短描述；可空（候选筛选 / 规划目录用）
 * @param skillManual  技能操作说明/手册文本；可空（执行/ReAct 用）
 * @param contract     技能契约；可空
 * @author alpha
 * @since 2026/8/21
 */
public record Skill(String skillKey, List<String> toolNames, @Nullable String semanticDesc,
                    @Nullable String skillManual, @Nullable SkillContract contract) {

    public Skill {
        // 工具名列表固化为不可变副本
        toolNames = List.copyOf(toolNames);
    }

    /**
     * 仅带技能键的轻量构造：无工具、无短描述、无手册、无契约。
     *
     * @param skillKey 技能唯一键
     */
    public Skill(String skillKey) {
        this(skillKey, List.of(), null, null, null);
    }

    /**
     * 带手册与契约、暂无业务工具与短描述的构造。
     *
     * @param skillKey    技能唯一键
     * @param skillManual 操作手册；可空
     * @param contract    契约；可空
     */
    public Skill(String skillKey, @Nullable String skillManual, @Nullable SkillContract contract) {
        this(skillKey, List.of(), null, skillManual, contract);
    }
}
