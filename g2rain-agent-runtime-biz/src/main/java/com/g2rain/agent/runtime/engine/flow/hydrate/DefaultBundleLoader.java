package com.g2rain.agent.runtime.engine.flow.hydrate;


import com.g2rain.agent.runtime.client.AgentProfiles;
import com.g2rain.agent.runtime.domain.hydrate.ArgSpecHydrate;
import com.g2rain.agent.runtime.domain.hydrate.ContractVersion;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.spi.SpecQuery;
import com.g2rain.agent.runtime.spi.SpecSet;
import com.g2rain.agent.runtime.spi.SpecSource;
import com.g2rain.agent.runtime.spi.SpecSourceField;
import com.g2rain.agent.runtime.spi.SpecSourceType;
import com.g2rain.agent.runtime.spi.SpecUnavailableException;
import com.g2rain.agent.studio.vo.AgentBundleVo;
import com.g2rain.common.exception.BusinessException;
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
 * 契约须声明 {@code source.type}：{@link SpecSourceType#CONTRACT} 用契约自身的 {@code tools}，
 * 其余类型由对应的 {@link SpecSource} 整体替换 {@code tools}。
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
     * 动作语义来源表：按 {@code source.type} 找实现。
     */
    private final SpecSources specSources;

    /**
     * 加载 Agent 技能列表；无 Bundle 或无可用技能时为 {@code null}。
     *
     * @param agentId         Agent 主键
     * @param pins            技能键 → 已锁定的版本标记
     * @param skillRevisionId 开任务时锁定的技能修订；可空
     * @return 技能列表；不可用时为 {@code null}
     */
    @Override
    public @Nullable List<Skill> load(long agentId, Map<String, String> pins, @Nullable Long skillRevisionId) {
        AgentBundleVo bundle = agentProfiles.loadCapabilityBundle(agentId, skillRevisionId);
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
     * 解析技能契约；空白、解析失败或 source 配置错误返回 {@code null}。
     * 来源暂时不可用时抛 {@link BusinessException}（{@link RuntimeErrorCode#DEPENDENCY_UNAVAILABLE}）。
     *
     * <p>顺序：JSON 解析 → 检查根属性 {@code version} → 若有 source 则替换 tools → ArgSpecHydrate。</p>
     *
     * @param skillKey      技能键（仅用于告警与查询）
     * @param skillContract 契约原文；可空
     * @param toolNames     业务工具名；在 source.load 之前算好
     * @param pin           任务已锁定的版本；可空
     * @return 契约与 pin；配置错误时为 {@code null}
     */
    private @Nullable ParsedContract parseContract(String skillKey, @Nullable String skillContract,
                                                   List<String> toolNames, @Nullable String pin) {
        if (Strings.isBlank(skillContract)) {
            log.error("[BundleLoader] skill unavailable skillKey={} reason=contract_blank", skillKey);
            return null;
        }

        SpecSourceType type = null;
        try {
            SkillContract parsed = JSON.str2obj(skillContract, SkillContract.class);
            if (Objects.isNull(parsed)) {
                log.error("[BundleLoader] skill unavailable skillKey={} reason=contract_null", skillKey);
                return null;
            }

            // 根属性 version：不通过则本轮直接失败
            requireAcceptedVersion(skillKey, parsed.getVersion());

            JsonNode source = parsed.getSource();
            String typeName = typeOf(source);
            if (Strings.isBlank(typeName)) {
                log.error("[BundleLoader] skill unavailable skillKey={} reason=source_type_missing", skillKey);
                return null;
            }

            type = SpecSourceType.of(typeName);
            if (Objects.isNull(type)) {
                log.error("[BundleLoader] skill unavailable skillKey={} reason=source_type_invalid type={}",
                    skillKey, typeName);
                return null;
            }

            String resolvedPin = null;
            if (type != SpecSourceType.CONTRACT) {
                SpecSet set = loadSpecSet(skillKey, type, Objects.requireNonNull(source), toolNames, pin);
                if (Objects.isNull(set)) {
                    return null;
                }

                parsed.setTools(new HashMap<>(set.tools()));
                resolvedPin = set.pin();
            }

            ArgSpecHydrate.hydrate(parsed);
            return new ParsedContract(parsed, resolvedPin);
        } catch (SpecUnavailableException ex) {
            SpecSourceType unavailableType = Objects.requireNonNull(type);
            log.error("[BundleLoader] skill unavailable skillKey={} reason=spec_source_unavailable type={}",
                skillKey, unavailableType, ex);
            throw new BusinessException(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, ex, unavailableType.name());
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("[BundleLoader] skill unavailable skillKey={} reason=contract_parse_failed", skillKey, ex);
            return null;
        }
    }

    /**
     * 检查契约根属性 {@code version}；不通过则抛对应错误码。
     *
     * @param skillKey 技能键
     * @param version  契约上的版本串；可空
     */
    private static void requireAcceptedVersion(String skillKey, @Nullable String version) {
        ContractVersion.Verdict verdict = ContractVersion.check(version);
        if (verdict == ContractVersion.Verdict.ACCEPTED) {
            return;
        }

        String reported = Objects.requireNonNullElse(version, "");
        if (verdict == ContractVersion.Verdict.INVALID) {
            throw new BusinessException(RuntimeErrorCode.CONTRACT_VERSION_INVALID, skillKey,
                ContractVersion.CURRENT);
        }

        if (verdict == ContractVersion.Verdict.OUTDATED) {
            throw new BusinessException(RuntimeErrorCode.CONTRACT_VERSION_OUTDATED, skillKey, reported,
                ContractVersion.CURRENT);
        }

        throw new BusinessException(RuntimeErrorCode.CONTRACT_VERSION_AHEAD, skillKey, reported,
            ContractVersion.CURRENT);
    }

    /**
     * 契约 {@code source.type}。
     *
     * @param source 来源声明；可空
     * @return 来源类型；未声明或不是对象时为 {@code null}
     */
    private static @Nullable String typeOf(@Nullable JsonNode source) {
        if (Objects.isNull(source) || !source.isObject()) {
            return null;
        }

        JsonNode type = source.get(SpecSourceField.TYPE.key());
        return Objects.nonNull(type) && type.isString() ? Strings.blankToNull(type.asString().trim()) : null;
    }

    /**
     * 按来源类型选择 {@link SpecSource} 并加载动作语义；配置错误时打日志并返回 {@code null}。
     * {@link SpecUnavailableException} 原样抛出，由 {@link #parseContract} 转为依赖不可用。
     *
     * @param skillKey  技能键
     * @param type      来源类型（非 {@link SpecSourceType#CONTRACT}）
     * @param source    来源声明原文
     * @param toolNames 业务工具名
     * @param pin       已锁定版本；可空
     * @return 语义集合；配置错误时为 {@code null}
     */
    private @Nullable SpecSet loadSpecSet(String skillKey, SpecSourceType type, JsonNode source, List<String> toolNames,
                                          @Nullable String pin) {
        try {
            SpecSource matched = specSources.of(type);
            if (Objects.isNull(matched)) {
                log.error("[BundleLoader] skill unavailable skillKey={} reason=spec_source_missing type={}",
                    skillKey, type);
                return null;
            }

            return matched.load(new SpecQuery(skillKey, toolNames, source, pin));
        } catch (SpecUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("[BundleLoader] skill unavailable skillKey={} reason=spec_source_failed", skillKey, ex);
            return null;
        }
    }

    /**
     * 解析结果：契约与可选版本标记。
     *
     * @param contract 技能契约
     * @param pin      版本标记；来源为 {@link SpecSourceType#CONTRACT} 时为 {@code null}
     */
    private record ParsedContract(SkillContract contract, @Nullable String pin) {

    }
}
