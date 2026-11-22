package com.g2rain.agent.runtime.engine.flow.hydrate;

import com.g2rain.agent.runtime.client.AgentProfiles;
import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.spec.ArgSpec;
import com.g2rain.agent.runtime.spec.ToolSpec;
import com.g2rain.agent.runtime.spec.ValueRef;
import com.g2rain.agent.runtime.spi.SpecQuery;
import com.g2rain.agent.runtime.spi.SpecSet;
import com.g2rain.agent.runtime.spi.SpecSource;
import com.g2rain.agent.runtime.spi.SpecSourceType;
import com.g2rain.agent.runtime.spi.SpecUnavailableException;
import com.g2rain.agent.runtime.support.PlatformCatalog;
import com.g2rain.agent.studio.vo.AgentBundleVo;
import com.g2rain.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultBundleLoader} 的 source 替换与版本 pin 测试。
 *
 * @author alpha
 * @since 2026/11/17
 */
class DefaultBundleLoaderTest {

    private static final long AGENT_ID = 1L;

    private static final String SKILL_KEY = "cms.article";

    private static final String TOOL_NAME = "native_get";

    private AgentProfiles agentProfiles;

    private DefaultBundleLoader loader;

    private SpecSource specSource;

    @BeforeEach
    void setUp() {
        agentProfiles = mock(AgentProfiles.class);
        ContractValidator validator = new ContractValidator(new PlatformCatalog(new RuntimeProperties()));
        specSource = mock(SpecSource.class);
        when(specSource.type()).thenReturn(SpecSourceType.ONTOLOGY);
        loader = new DefaultBundleLoader(agentProfiles, validator, new SpecSources(List.of(specSource)));
    }

    @Test
    void load_contractSource_shouldKeepContractToolsAndNullPin() {
        when(agentProfiles.loadCapabilityBundle(AGENT_ID)).thenReturn(bundle(contractOwnSource()));

        List<Skill> skills = loader.load(AGENT_ID, Map.of());

        assertNotNull(skills);
        Skill skill = skills.getFirst();
        assertNull(skill.pin());
        assertTrue(Objects.requireNonNull(skill.contract()).getTools().containsKey(TOOL_NAME));
        ArgSpec arg = Objects.requireNonNull(skill.contract()).getTools().get(TOOL_NAME).getArgs().get("title");
        assertNotNull(arg.getValueRef());
        assertEquals(ValueRef.Bag.INPUTS, arg.getValueRef().bag());
    }

    @Test
    void load_withSourceAndImpl_shouldReplaceToolsAndSetPin() {
        when(agentProfiles.loadCapabilityBundle(AGENT_ID)).thenReturn(bundle(contractWithSource()));
        when(specSource.load(any(SpecQuery.class))).thenReturn(new SpecSet(Map.of(TOOL_NAME, replacementTool()), "pin-1"));

        List<Skill> skills = loader.load(AGENT_ID, Map.of());

        assertNotNull(skills);
        Skill skill = skills.getFirst();
        assertEquals("pin-1", skill.pin());
        assertEquals(1, Objects.requireNonNull(skill.contract()).getTools().size());
        ArgSpec arg = Objects.requireNonNull(skill.contract()).getTools().get(TOOL_NAME).getArgs().get("id");
        assertNotNull(arg.getValueRef());
        assertEquals(ValueRef.Bag.INPUTS, arg.getValueRef().bag());
        assertEquals("id", arg.getValueRef().path());
    }

    @Test
    void load_withUnknownSourceType_shouldDropSkill() {
        when(agentProfiles.loadCapabilityBundle(AGENT_ID)).thenReturn(bundle("""
            {"source":{"type":"OTHER"},"tools":{}}
            """));

        assertNull(loader.load(AGENT_ID, Map.of()));
    }

    @Test
    void load_withoutSourceType_shouldDropSkill() {
        when(agentProfiles.loadCapabilityBundle(AGENT_ID)).thenReturn(bundle("""
            {"tools":{"%s":{}}}
            """.formatted(TOOL_NAME)));

        assertNull(loader.load(AGENT_ID, Map.of()));
    }

    @Test
    void contractType_shouldFailRegistry() {
        SpecSource contract = mock(SpecSource.class);
        when(contract.type()).thenReturn(SpecSourceType.CONTRACT);

        assertThrows(IllegalStateException.class, () -> new SpecSources(List.of(contract)));
    }

    @Test
    void duplicateType_shouldFailRegistry() {
        SpecSource other = mock(SpecSource.class);
        when(other.type()).thenReturn(SpecSourceType.ONTOLOGY);

        assertThrows(IllegalStateException.class, () -> new SpecSources(List.of(specSource, other)));
    }

    @Test
    void load_whenSourceThrows_shouldDropSkillWithoutFallback() {
        when(agentProfiles.loadCapabilityBundle(AGENT_ID)).thenReturn(bundle(contractWithSource()));
        when(specSource.load(any(SpecQuery.class))).thenThrow(new IllegalStateException("boom"));

        assertNull(loader.load(AGENT_ID, Map.of()));
    }

    /** H9：某个技能的 SpecSource 抛 SpecUnavailableException → DEPENDENCY_UNAVAILABLE，其他技能也不返回。 */
    @Test
    void h9_load_specUnavailable_throwsDependencyUnavailable() {
        when(agentProfiles.loadCapabilityBundle(AGENT_ID)).thenReturn(bundleTwoSkills(
            SKILL_KEY, contractWithSource(),
            "cms.other", contractOwnSource()));
        when(specSource.load(any(SpecQuery.class)))
            .thenThrow(new SpecUnavailableException(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, "ONTOLOGY"));

        BusinessException ex = assertThrows(BusinessException.class, () -> loader.load(AGENT_ID, Map.of()));
        assertEquals(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE.code(), ex.getErrorCode());
        assertEquals(SpecSourceType.ONTOLOGY.name(), ex.getIndexArgs()[0]);
    }

    /** H10：某个技能的 SpecSource 抛普通 RuntimeException → 丢弃该技能，其余正常返回。 */
    @Test
    void h10_load_sourceRuntimeException_dropsSkillKeepsOthers() {
        when(agentProfiles.loadCapabilityBundle(AGENT_ID)).thenReturn(bundleTwoSkills(
            SKILL_KEY, contractWithSource(),
            "cms.other", contractOwnSource()));
        when(specSource.load(any(SpecQuery.class))).thenThrow(new IllegalStateException("boom"));

        List<Skill> skills = loader.load(AGENT_ID, Map.of());

        assertNotNull(skills);
        assertEquals(1, skills.size());
        assertEquals("cms.other", skills.getFirst().skillKey());
    }

    @Test
    void load_shouldPassPinnedVersionIntoQuery() {
        when(agentProfiles.loadCapabilityBundle(AGENT_ID)).thenReturn(bundle(contractWithSource()));
        when(specSource.load(any(SpecQuery.class))).thenReturn(new SpecSet(Map.of(TOOL_NAME, new ToolSpec()), "pin-1"));

        loader.load(AGENT_ID, Map.of(SKILL_KEY, "locked-pin"));

        ArgumentCaptor<SpecQuery> captor = ArgumentCaptor.forClass(SpecQuery.class);
        verify(specSource).load(captor.capture());
        assertEquals("locked-pin", captor.getValue().pin());
        assertEquals(SKILL_KEY, captor.getValue().skillKey());
        assertEquals(List.of(TOOL_NAME), captor.getValue().toolNames());
    }

    private static AgentBundleVo bundle(String contractJson) {
        return bundleTwoSkills(SKILL_KEY, contractJson);
    }

    private static AgentBundleVo bundleTwoSkills(String key1, String contract1) {
        return bundleTwoSkills(key1, contract1, null, null);
    }

    private static AgentBundleVo bundleTwoSkills(String key1, String contract1,
                                                String key2, String contract2) {
        AgentBundleVo.ToolBriefVo tool = new AgentBundleVo.ToolBriefVo();
        tool.setToolName(TOOL_NAME);
        AgentBundleVo.SkillBriefVo first = skillBrief(key1, contract1, tool);
        AgentBundleVo bundle = new AgentBundleVo();
        if (Objects.isNull(key2)) {
            bundle.setSkills(List.of(first));
            return bundle;
        }
        bundle.setSkills(List.of(first, skillBrief(key2, contract2, tool)));
        return bundle;
    }

    private static AgentBundleVo.SkillBriefVo skillBrief(String skillKey, String contractJson,
                                                         AgentBundleVo.ToolBriefVo tool) {
        AgentBundleVo.SkillBriefVo skill = new AgentBundleVo.SkillBriefVo();
        skill.setSkillKey(skillKey);
        skill.setSkillName(skillKey);
        skill.setSkillContract(contractJson);
        skill.setTools(List.of(tool));
        return skill;
    }

    private static String contractOwnSource() {
        return """
            {"source":{"type":"CONTRACT"},"tools":{"%s":{"args":{"title":{"from":"inputs.title"}}}}}
            """.formatted(TOOL_NAME);
    }

    private static String contractWithSource() {
        return """
            {"source":{"type":"ONTOLOGY","ontology":"cms"},"tools":{"old_tool":{}}}
            """;
    }

    private static ToolSpec replacementTool() {
        ArgSpec arg = new ArgSpec();
        arg.setFrom("inputs.id");
        ToolSpec tool = new ToolSpec();
        tool.setArgs(new HashMap<>(Map.of("id", arg)));
        return tool;
    }
}
