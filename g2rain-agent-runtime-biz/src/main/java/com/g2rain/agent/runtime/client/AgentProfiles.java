package com.g2rain.agent.runtime.client;


import com.g2rain.agent.studio.vo.AgentBundleVo;
import com.g2rain.agent.studio.vo.AgentCatalogItemVo;
import com.g2rain.agent.studio.vo.SkillDigestVo;
import com.g2rain.common.utils.Collections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * AgentProfiles：Agent 档案适配门面——查目录 / 解析人设 / 技能摘要 / 能力包。
 * <p>
 * Turn（本轮）= 一次用户请求从进入到产出结果的完整处理。
 * 调用 {@link AgentProfileClient}，失败或空数据时返回空列表或 null。
 * </p>
 *
 * @author alpha
 * @since 2026/8/28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentProfiles {

    /**
     * Studio Agent 档案 Feign 客户端。
     */
    private final AgentProfileClient profileClient;

    /**
     * 拉取当前生效的 Agent 目录。
     *
     * @return 目录项；失败或空则空列表
     */
    public List<AgentCatalogItemVo> listActiveCatalog() {
        var result = profileClient.selectActiveCatalog();
        // 远程失败或空数据：当作无目录
        if (Objects.isNull(result) || !result.isSuccess()) {
            log.warn("[AgentProfiles] listActiveCatalog failed success={}",
                Objects.isNull(result) ? null : result.isSuccess());
            return List.of();
        }

        return result.getData();
    }

    /**
     * 按 agentId 解析档案条目。
     *
     * @param agentId Agent ID
     * @return 档案；失败可空
     */
    public @Nullable AgentCatalogItemVo resolveProfile(Long agentId) {
        var result = profileClient.resolveProfile(agentId);
        // 失败则无档案
        if (Objects.isNull(result) || !result.isSuccess()) {
            log.warn("[AgentProfiles] resolveProfile failed agentId={} success={}",
                agentId, Objects.isNull(result) ? null : result.isSuccess());
            return null;
        }

        return result.getData();
    }

    /**
     * 拉取某 Agent 的技能短摘要列表。
     *
     * @param agentId Agent ID
     * @return 摘要列表；失败则空列表
     */
    public List<SkillDigestVo> listSkillDigest(Long agentId) {
        var result = profileClient.selectSkillDigest(agentId);
        // 失败返回空列表，不向上抛
        if (Objects.isNull(result) || !result.isSuccess()) {
            log.warn("[AgentProfiles] listSkillDigest failed agentId={} success={}",
                agentId, Objects.isNull(result) ? null : result.isSuccess());
            return List.of();
        }

        return result.getData();
    }

    /**
     * 加载能力包；无 skills 视为不可用。
     *
     * @param agentId Agent ID
     * @return 能力包；失败或无技能可空
     */
    public @Nullable AgentBundleVo loadCapabilityBundle(Long agentId) {
        var result = profileClient.selectCapabilityBundle(agentId);
        // 远程失败视为未加载
        if (Objects.isNull(result) || !result.isSuccess()) {
            log.warn("[AgentProfiles] loadCapabilityBundle failed agentId={} success={}",
                agentId, Objects.isNull(result) ? null : result.isSuccess());
            return null;
        }

        AgentBundleVo bundle = result.getData();
        // 空技能包对 Runtime 无意义，直接当作未加载
        if (Collections.isEmpty(bundle.getSkills())) {
            return null;
        }

        return bundle;
    }
}
