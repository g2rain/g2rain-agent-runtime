package com.g2rain.agent.runtime.engine.flow.decision;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.g2rain.agent.runtime.adapter.AgentProfiles;
import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.studio.enums.ResourceStatus;
import com.g2rain.agent.studio.vo.AgentCatalogItemVo;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * DefaultAgentCatalog（默认 Agent 目录）：经 {@link AgentProfiles} 读启用目录，组装 {@link AgentSelection}。
 * <p>
 * {@link #findActiveById} 先扫启用列表，未命中再单条 resolve 并校验 ACTIVE；
 * {@link #listActive} 直接映射启用目录。人设五段（身份/使命/边界/风格/原则）序列化为 profileText。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class DefaultAgentCatalog implements AgentCatalog {

    /**
     * JSON 编解码器：把人设五段打成 profileText。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * Studio/画像适配：列启用目录、按 id resolve。
     */
    private final AgentProfiles agentProfiles;

    /**
     * 按 id 取启用中的选人画像：先列表命中，再单条 resolve 兜底。
     *
     * @param agentId Agent 主键
     * @return 启用画像；不存在或未启用为 {@code null}
     */
    @Override
    public @Nullable AgentSelection findActiveById(Long agentId) {
        // ① 目录热路径：ACTIVE 列表里按 id 命中即可，避免多余 resolve
        return agentProfiles.listActiveCatalog().stream()
            .filter(item -> agentId.equals(item.getAgentId()))
            .findFirst().map(DefaultAgentCatalog::toSelection)
            // ② 目录未扫到：可能是刚启用或列表缓存口径差异 → 单条 resolve + ACTIVE 校验
            .orElseGet(() -> resolveActive(agentId));
    }

    /**
     * 列表未命中时单条 resolve，并要求状态为 ACTIVE。
     *
     * @param agentId Agent 主键
     * @return 启用画像；查无或未启用为 {@code null}
     */
    private @Nullable AgentSelection resolveActive(Long agentId) {
        AgentCatalogItemVo profile = agentProfiles.resolveProfile(agentId);
        // 查无或未启用：与 Entry 显式 agentId 校验口径一致
        if (Objects.isNull(profile)) {
            return null;
        }

        if (ResourceStatus.isInactive(profile.getStatus())) {
            return null;
        }

        return toSelection(profile);
    }

    /**
     * 目录项 → 选人画像（含 profileText）。
     *
     * @param item 目录/画像 VO
     * @return 选人结果
     */
    private static AgentSelection toSelection(AgentCatalogItemVo item) {
        return AgentSelection.of(
            item.getAgentId(),
            item.getAgentName(),
            ProfileText.jsonOf(item)
        );
    }

    /**
     * 列出全部启用中的选人画像。
     *
     * @return 启用列表
     */
    @Override
    public List<AgentSelection> listActive() {
        return AgentSelection.of(agentProfiles.listActiveCatalog());
    }

    /**
     * 人设五段的序列化形状；空段不输出。
     *
     * @param identity  身份说明
     * @param mission   使命说明
     * @param boundary  边界说明
     * @param style     风格说明
     * @param principle 原则说明
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ProfileText(@Nullable String identity, @Nullable String mission, @Nullable String boundary,
                               @Nullable String style, @Nullable String principle) {

        /**
         * 从目录项抽出五段人设并打成 JSON；全空则返回 {@code null}。
         *
         * @param item 目录/画像 VO
         * @return profileText；无可写内容时为 {@code null}
         */
        static @Nullable String jsonOf(AgentCatalogItemVo item) {
            ProfileText p = new ProfileText(
                Strings.blankToNull(item.getIdentityDesc()),
                Strings.blankToNull(item.getMissionDesc()),
                Strings.blankToNull(item.getBoundaryDesc()),
                Strings.blankToNull(item.getStyleDesc()),
                Strings.blankToNull(item.getPrincipleDesc())
            );

            // 五段皆空 → 不写 "{}"，下游按无 profileText 处理
            if (Stream.of(p.identity(), p.mission(), p.boundary(), p.style(), p.principle()).allMatch(Objects::isNull)) {
                return null;
            }

            return JSON.obj2str(p);
        }
    }
}
