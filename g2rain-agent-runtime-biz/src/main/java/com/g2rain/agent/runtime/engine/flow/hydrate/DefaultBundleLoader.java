package com.g2rain.agent.runtime.engine.flow.hydrate;


import com.g2rain.agent.runtime.client.AgentProfiles;
import com.g2rain.agent.runtime.domain.hydrate.ArgSpecHydrate;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.spi.SpecQuery;
import com.g2rain.agent.runtime.spi.SpecSet;
import com.g2rain.agent.runtime.spi.SpecSource;
import com.g2rain.agent.studio.vo.AgentBundleVo;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * DefaultBundleLoader：经 {@link AgentProfiles} 拉 Bundle，转成 {@link Skill} 列表。
 * <p>
 * 技能无有效 skillKey 或契约不可用则丢弃；契约按 {@link SkillContract} 解析，失败即 fail-closed。
 * 声明 {@code source} 时由 {@link SpecSource} 整体替换 {@code tools}。
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
     * 动作语义来源；没有实现时为空列表。
     */
    private final List<SpecSource> specSources;

    /**
     * 加载 Agent 技能列表；无 Bundle 或无可用技能时为 {@code null}。
     *
     * @param agentId Agent 主键
     * @param pins    技能键 → 已锁定的版本标记
     * @return 技能列表；不可用时为 {@code null}
     */
    @Override
    public @Nullable List<Skill> load(long agentId, Map<String, String> pins) {
        AgentBundleVo bundle = agentProfiles.loadCapabilityBundle(agentId);
        if (Objects.isNull(bundle)) {
            return null;
        }

        return toSkills(bundle, pins);
    }

    /**
     * Bundle VO → 技能列表。
     *
     * @param vo   Studio Bundle
     * @param pins 已锁定版本
     * @return 技能列表；无有效技能时为 {@code null}
     */
    private @Nullable List<Skill> toSkills(AgentBundleVo vo, Map<String, String> pins) {
        List<Skill> result = new ArrayList<>();
        var skills = vo.getSkills();
        if (Objects.nonNull(skills)) {
            for (var brief : skills) {
                String skillKey = brief.getSkillKey();
                if (Strings.isBlank(skillKey)) {
                    continue;
                }

                Skill skill = toSkill(brief, pins);
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
     * 技能摘要 → 领域 Skill（含工具名、技能契约与可选版本标记）。
     * <p>契约空白、解析失败、source 不可用或校验失败则该 Skill 不可用，返回 {@code null}（fail-closed）。</p>
     *
     * @param brief Bundle 内技能摘要
     * @param pins  已锁定版本
     * @return 技能领域对象；契约不可用时为 {@code null}
     */
    private @Nullable Skill toSkill(AgentBundleVo.SkillBriefVo brief, Map<String, String> pins) {
        String skillKey = brief.getSkillKey().trim();
        List<String> toolNames = Stream.ofNullable(brief.getTools())
            .flatMap(List::stream)
            .filter(tool -> Strings.isNotBlank(tool.getToolName()))
            .map(tool -> tool.getToolName().trim())
            .toList();

        ParsedContract parsed = parseContract(skillKey, brief.getSkillContract(), toolNames, pins.get(skillKey));
        if (Objects.isNull(parsed) || !contractValidator.valid(parsed.contract())) {
            if (Objects.nonNull(parsed)) {
                log.error("[BundleLoader] skill unavailable skillKey={} reason=contract_invalid", skillKey);
            }
            // fail-closed：契约无效的技能不得进入规划
            return null;
        }

        return new Skill(
            skillKey,
            Strings.blankToNull(brief.getSkillName()),
            toolNames,
            Strings.blankToNull(brief.getSemanticDesc()),
            brief.getSkillManual(),
            parsed.contract(),
            parsed.pin()
        );
    }

    /**
     * 解析技能契约；空白、解析失败或 source 不可用返回 {@code null}。
     *
     * <p>顺序：JSON 解析 → 若有 source 则替换 tools → ArgSpecHydrate。</p>
     *
     * @param skillKey      技能键（仅用于告警与查询）
     * @param skillContract 契约原文；可空
     * @param toolNames     业务工具名；在 source.load 之前算好
     * @param pin           任务已锁定的版本；可空
     * @return 契约与 pin；不可用时为 {@code null}
     */
    private @Nullable ParsedContract parseContract(String skillKey, @Nullable String skillContract,
                                                   List<String> toolNames, @Nullable String pin) {
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

            String resolvedPin = null;
            JsonNode source = parsed.getSource();
            if (Objects.nonNull(source) && !source.isNull() && !source.isMissingNode()) {
                SpecSet set = loadSpecSet(skillKey, source, toolNames, pin);
                if (Objects.isNull(set)) {
                    return null;
                }

                parsed.setTools(new HashMap<>(set.tools()));
                resolvedPin = set.pin();
            }

            ArgSpecHydrate.hydrate(parsed);
            return new ParsedContract(parsed, resolvedPin);
        } catch (RuntimeException ex) {
            log.error("[BundleLoader] skill unavailable skillKey={} reason=contract_parse_failed", skillKey, ex);
            return null;
        }
    }

    /**
     * 选择 {@link SpecSource} 并加载动作语义；失败时打日志并返回 {@code null}。
     *
     * @param skillKey  技能键
     * @param source    来源声明原文
     * @param toolNames 业务工具名
     * @param pin       已锁定版本；可空
     * @return 语义集合；不可用时为 {@code null}
     */
    private @Nullable SpecSet loadSpecSet(String skillKey, JsonNode source, List<String> toolNames, @Nullable String pin) {
        try {
            SpecSource matched = null;
            for (SpecSource candidate : specSources) {
                // supports 抛异常与 load 失败同口径，避免落到外层 contract_parse_failed
                if (candidate.supports(source)) {
                    matched = candidate;
                    break;
                }
            }

            if (Objects.isNull(matched)) {
                log.error("[BundleLoader] skill unavailable skillKey={} reason=spec_source_missing", skillKey);
                return null;
            }

            return matched.load(new SpecQuery(skillKey, toolNames, source, pin));
        } catch (RuntimeException ex) {
            log.error("[BundleLoader] skill unavailable skillKey={} reason=spec_source_failed", skillKey, ex);
            return null;
        }
    }

    /**
     * 解析结果：契约与可选版本标记。
     *
     * @param contract 技能契约
     * @param pin      版本标记；无 source 时为 {@code null}
     */
    private record ParsedContract(SkillContract contract, @Nullable String pin) {

    }
}
