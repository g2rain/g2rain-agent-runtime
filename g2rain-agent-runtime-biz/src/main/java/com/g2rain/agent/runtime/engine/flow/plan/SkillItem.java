package com.g2rain.agent.runtime.engine.flow.plan;

import com.g2rain.agent.runtime.domain.hydrate.Skill;

import java.util.Objects;

/**
 * 技能筛选所需的短目录：仅包含 key 和语义短描述，不含操作手册。
 *
 * @author alpha
 * @since 2026/10/12
 *
 * @param skillKey     技能键
 * @param semanticDesc 语义短描述
 */
record SkillItem(String skillKey, String semanticDesc) {
    /**
     * 由技能构造短目录项；缺语义描述时用空串。
     *
     * @param skill 技能
     * @return 短目录项
     */
    static SkillItem from(Skill skill) {
        return new SkillItem(skill.skillKey(), Objects.toString(skill.semanticDesc(), ""));
    }
}
