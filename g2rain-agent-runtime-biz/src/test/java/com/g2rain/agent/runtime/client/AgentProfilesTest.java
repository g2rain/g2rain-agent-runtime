package com.g2rain.agent.runtime.client;

import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.studio.vo.AgentBundleVo;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.model.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentProfiles#loadCapabilityBundle} 用例 H1、H2。
 *
 * @author alpha
 * @since 2026/11/22
 */
class AgentProfilesTest {

    private AgentProfileClient profileClient;

    private AgentProfiles profiles;

    @BeforeEach
    void setUp() {
        profileClient = mock(AgentProfileClient.class);
        profiles = new AgentProfiles(profileClient);
    }

    /** H1：远程返回失败 → DEPENDENCY_UNAVAILABLE。 */
    @Test
    void h1_loadCapabilityBundle_remoteFailed_throwsDependencyUnavailable() {
        @SuppressWarnings("unchecked")
        Result<AgentBundleVo> failed = mock(Result.class);
        when(failed.isSuccess()).thenReturn(false);
        when(profileClient.selectCapabilityBundle(1L, null)).thenReturn(failed);

        assertThatThrownBy(() -> profiles.loadCapabilityBundle(1L, null))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE.code());
    }

    /** H2：成功但技能为空 → null。 */
    @Test
    void h2_loadCapabilityBundle_emptySkills_returnsNull() {
        AgentBundleVo bundle = new AgentBundleVo();
        bundle.setSkills(List.of());
        when(profileClient.selectCapabilityBundle(1L, null)).thenReturn(Result.success(bundle));

        assertThat(profiles.loadCapabilityBundle(1L, null)).isNull();
    }

    @Test
    void loadCapabilityBundle_passesSkillRevisionId() {
        AgentBundleVo bundle = new AgentBundleVo();
        AgentBundleVo.SkillBriefVo skill = new AgentBundleVo.SkillBriefVo();
        skill.setSkillKey("cms.a");
        bundle.setSkills(List.of(skill));
        when(profileClient.selectCapabilityBundle(1L, 9001L)).thenReturn(Result.success(bundle));

        assertThat(profiles.loadCapabilityBundle(1L, 9001L)).isSameAs(bundle);
    }
}
