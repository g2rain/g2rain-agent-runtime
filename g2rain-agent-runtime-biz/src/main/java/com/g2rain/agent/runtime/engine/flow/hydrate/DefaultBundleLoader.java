package com.g2rain.agent.runtime.engine.flow.hydrate;


import com.g2rain.agent.runtime.adapter.AgentProfiles;
import com.g2rain.agent.runtime.domain.hydrate.ArgSpecHydrate;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.engine.hydrate.ContractValidator;
import com.g2rain.agent.studio.vo.AgentBundleVo;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * DefaultBundleLoader：经 {@link AgentProfiles} 拉 Bundle，转成 {@link Skill} 列表。
 * <p>
 * 技能无有效 skillKey 或契约不可用则丢弃；契约按 {@link SkillContract} 解析，失败即 fail-closed。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultBundleLoader implements BundleLoader {

    /**
     * JSON 编解码器：解析技能契约字符串。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 画像适配：按 agentId 加载能力 Bundle。
     */
    private final AgentProfiles agentProfiles;

    /**
     * 契约校验：不通过则该技能不可进入规划。
     */
    private final ContractValidator contractValidator;

    /**
     * 加载 Agent 技能列表；无 Bundle 或无可用技能时为 {@code null}。
     *
     * @param agentId Agent 主键
     * @return 技能列表；不可用时为 {@code null}
     */
    @Override
    public @Nullable List<Skill> load(long agentId) {
        AgentBundleVo bundle = agentProfiles.loadCapabilityBundle(agentId);
        if (Objects.isNull(bundle)) {
            log.info("[BundleLoader] empty bundle agentId={}", agentId);
            return null;
        }

        List<Skill> skills = toSkills(bundle);
        log.info("[BundleLoader] loaded agentId={} skillKeys={}", agentId, JSON.obj2str(skills));
        return skills;
    }

    /**
     * Bundle VO → 技能列表。
     *
     * @param vo Studio Bundle
     * @return 技能列表；无有效技能时为 {@code null}
     */
    private @Nullable List<Skill> toSkills(AgentBundleVo vo) {
        List<Skill> result = new ArrayList<>();
        var skills = vo.getSkills();
        if (Objects.nonNull(skills)) {
            for (var brief : skills) {
                String skillKey = brief.getSkillKey();
                if (Strings.isBlank(skillKey)) {
                    continue;
                }

                Skill skill = toSkill(brief);
                if (Objects.isNull(skill)) {
                    continue;
                }

                result.add(skill);
            }
        }

        // 无可用技能与无 Bundle 同口径：返回 null，让规划失败而不是空图
        return result.isEmpty() ? null : result;
    }

    /**
     * 技能摘要 → 领域 Skill（含工具名与技能契约）。
     * <p>契约空白或解析失败则该 Skill 不可用，返回 {@code null}（fail-closed）。</p>
     *
     * @param brief Bundle 内技能摘要
     * @return 技能领域对象；契约不可用时为 {@code null}
     */
    private @Nullable Skill toSkill(AgentBundleVo.SkillBriefVo brief) {
        String skillKey = brief.getSkillKey().trim();
        SkillContract contract = parseContract(skillKey, brief.getSkillContract());
        if (Objects.isNull(contract) || !contractValidator.valid(contract)) {
            if (Objects.nonNull(contract)) {
                log.error("[BundleLoader] skill unavailable skillKey={} reason=contract_invalid", skillKey);
            }
            // fail-closed：契约无效的技能不得进入规划
            return null;
        }

        List<String> toolNames = Stream.ofNullable(brief.getTools())
            .flatMap(List::stream)
            .filter(tool -> Strings.isNotBlank(tool.getToolName()))
            .map(tool -> tool.getToolName().trim())
            .toList();

        return new Skill(
            skillKey,
            toolNames,
            Strings.blankToNull(brief.getSemanticDesc()),
            brief.getSkillManual(),
            contract
        );
    }

    /**
     * 解析技能契约；空白或解析失败返回 {@code null}，该 Skill 不可进入规划/执行。
     *
     * @param skillKey      技能键（仅用于告警）
     * @param skillContract 契约原文；可空
     * @return 契约对象；不可用时为 {@code null}
     */
    private static @Nullable SkillContract parseContract(String skillKey, @Nullable String skillContract) {
        if (Strings.isBlank(skillContract)) {
            log.error("[BundleLoader] skill unavailable skillKey={} reason=contract_blank", skillKey);
            return null;
        }

        try {
            SkillContract parsed = JSON.str2obj(skillContract, SkillContract.class);
            if (Objects.isNull(parsed)) {
                log.error("[BundleLoader] skill unavailable skillKey={} reason=contract_null", skillKey);
                return null;
            }

            ArgSpecHydrate.hydrate(parsed);
            return parsed;
        } catch (RuntimeException ex) {
            log.error("[BundleLoader] skill unavailable skillKey={} reason=contract_parse_failed", skillKey, ex);
            return null;
        }
    }
}
