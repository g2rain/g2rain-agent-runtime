package com.g2rain.agent.runtime.engine.flow.hydrate;

import com.g2rain.agent.runtime.client.AgentProfiles;
import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.spec.ArgSpec;
import com.g2rain.agent.runtime.spec.ToolSpec;
import com.g2rain.agent.runtime.spec.ValueRef;
import com.g2rain.agent.runtime.spi.SpecQuery;
import com.g2rain.agent.runtime.spi.SpecSet;
import com.g2rain.agent.runtime.spi.SpecSource;
import com.g2rain.agent.runtime.support.PlatformCatalog;
import com.g2rain.agent.studio.vo.AgentBundleVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        loader = new DefaultBundleLoader(agentProfiles, validator, List.of(specSource));
    }

    @Test
    void load_withoutSource_shouldKeepContractToolsAndNullPin() {
        when(agentProfiles.loadCapabilityBundle(AGENT_ID)).thenReturn(bundle(contractWithoutSource()));

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
        when(specSource.supports(any(JsonNode.class))).thenReturn(true);
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
    void load_withSourceButNoImpl_shouldDropSkill() {
        when(agentProfiles.loadCapabilityBundle(AGENT_ID)).thenReturn(bundle(contractWithSource()));
        when(specSource.supports(any(JsonNode.class))).thenReturn(false);

        assertNull(loader.load(AGENT_ID, Map.of()));
    }

    @Test
    void load_whenSourceThrows_shouldDropSkillWithoutFallback() {
        when(agentProfiles.loadCapabilityBundle(AGENT_ID)).thenReturn(bundle(contractWithSource()));
        when(specSource.supports(any(JsonNode.class))).thenReturn(true);
        when(specSource.load(any(SpecQuery.class))).thenThrow(new IllegalStateException("boom"));

        assertNull(loader.load(AGENT_ID, Map.of()));
    }

    @Test
    void load_whenSupportsThrows_shouldDropSkillAsSourceFailed() {
        when(agentProfiles.loadCapabilityBundle(AGENT_ID)).thenReturn(bundle(contractWithSource()));
        when(specSource.supports(any(JsonNode.class))).thenThrow(new IllegalStateException("boom"));

        assertNull(loader.load(AGENT_ID, Map.of()));
    }

    @Test
    void load_shouldPassPinnedVersionIntoQuery() {
        when(agentProfiles.loadCapabilityBundle(AGENT_ID)).thenReturn(bundle(contractWithSource()));
        when(specSource.supports(any(JsonNode.class))).thenReturn(true);
        when(specSource.load(any(SpecQuery.class))).thenReturn(new SpecSet(Map.of(TOOL_NAME, new ToolSpec()), "pin-1"));

        loader.load(AGENT_ID, Map.of(SKILL_KEY, "locked-pin"));

        ArgumentCaptor<SpecQuery> captor = ArgumentCaptor.forClass(SpecQuery.class);
        verify(specSource).load(captor.capture());
        assertEquals("locked-pin", captor.getValue().pin());
        assertEquals(SKILL_KEY, captor.getValue().skillKey());
        assertEquals(List.of(TOOL_NAME), captor.getValue().toolNames());
    }

    private static AgentBundleVo bundle(String contractJson) {
        AgentBundleVo.ToolBriefVo tool = new AgentBundleVo.ToolBriefVo();
        tool.setToolName(TOOL_NAME);
        AgentBundleVo.SkillBriefVo skill = new AgentBundleVo.SkillBriefVo();
        skill.setSkillKey(SKILL_KEY);
        skill.setSkillName(SKILL_KEY);
        skill.setSkillContract(contractJson);
        skill.setTools(List.of(tool));
        AgentBundleVo bundle = new AgentBundleVo();
        bundle.setSkills(List.of(skill));
        return bundle;
    }

    private static String contractWithoutSource() {
        return """
            {"tools":{"%s":{"args":{"title":{"from":"inputs.title"}}}}}
            """.formatted(TOOL_NAME);
    }

    private static String contractWithSource() {
        return """
            {"source":{"ontology":"cms"},"tools":{"old_tool":{}}}
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
