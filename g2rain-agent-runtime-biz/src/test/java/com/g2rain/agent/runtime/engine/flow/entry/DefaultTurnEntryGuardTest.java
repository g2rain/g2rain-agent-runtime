package com.g2rain.agent.runtime.engine.flow.entry;

import com.g2rain.agent.runtime.client.AgentProfiles;
import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.entry.EntryResult;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.studio.enums.ResourceStatus;
import com.g2rain.agent.studio.vo.AgentCatalogItemVo;
import com.g2rain.agent.studio.vo.SkillDigestVo;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.exception.SystemErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挂起任务的入口分流：补参直接续跑，审批与回查只认显式动作；指定技能入口校验。
 *
 * @author alpha
 * @since 2026/10/10
 */
class DefaultTurnEntryGuardTest {

    private AgentProfiles agentProfiles;
    private DefaultTurnEntryGuard guard;

    @BeforeEach
    void setUp() {
        agentProfiles = mock(AgentProfiles.class);
        guard = new DefaultTurnEntryGuard(agentProfiles, mock(RuntimeProperties.class));
    }

    @Test
    void followUpReplyGoesToClassification() {
        // 回复可能是回答，也可能是放弃或改办别的事，交给意图分类
        EntryResult result = guard.guard(context(CheckpointType.FOLLOW_UP, "上海"));

        assertThat(result.forceContinue()).isFalse();
        assertThat(result.failedReply()).isNull();
    }

    @Test
    void approvalWithTextGoesToClassification() {
        EntryResult result = guard.guard(context(CheckpointType.APPROVAL, "好的，继续"));

        assertThat(result.forceContinue()).isFalse();
        assertThat(result.failedReply()).isNull();
    }

    @Test
    void approvalWithoutTextIsRejected() {
        EntryResult result = guard.guard(context(CheckpointType.APPROVAL, null));

        assertThat(result.failedReply()).isNotNull();
    }

    @Test
    void skillKeyWithoutAgentId_isRejected() {
        TurnRequest request = new TurnRequest();
        request.setChannel("web");
        request.setSkillKey("cms.write");
        request.setUserInput("删分类");

        TurnScope turn = mock(TurnScope.class);
        when(turn.conversation()).thenReturn(new ConversationFence("conv-1", null, null, 1L, null, 0L));
        when(turn.task()).thenReturn(Optional.empty());
        when(turn.checkpoint()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.guard(new TurnContext(request, turn)))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(SystemErrorCode.PARAM_REQUIRED.code());
    }

    @Test
    void skillNotOfAgent_isRejected() {
        Long agentId = 9L;
        AgentCatalogItemVo profile = new AgentCatalogItemVo();
        profile.setStatus(ResourceStatus.ACTIVE.name());
        when(agentProfiles.resolveProfile(agentId)).thenReturn(profile);
        when(agentProfiles.requireSkillDigest(agentId)).thenReturn(List.of(digest("cms.read")));

        TurnRequest request = new TurnRequest();
        request.setChannel("web");
        request.setAgentId(agentId);
        request.setSkillKey("cms.write");
        request.setUserInput("删分类");

        TurnScope turn = mock(TurnScope.class);
        when(turn.conversation()).thenReturn(new ConversationFence("conv-1", null, null, 1L, null, 0L));
        when(turn.task()).thenReturn(Optional.empty());
        when(turn.checkpoint()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.guard(new TurnContext(request, turn)))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(RuntimeErrorCode.SKILL_NOT_OF_AGENT.code());
    }

    @Test
    void continueSuspended_withSkillKey_isIgnored() {
        Long agentId = 9L;
        AgentCatalogItemVo profile = new AgentCatalogItemVo();
        profile.setStatus(ResourceStatus.ACTIVE.name());
        when(agentProfiles.resolveProfile(agentId)).thenReturn(profile);

        TurnRequest request = new TurnRequest();
        request.setChannel("web");
        request.setAgentId(agentId);
        request.setSkillKey("not-belonging");
        request.setAction(ResumeAction.CONFIRM);

        TaskMetadata metadata = new TaskMetadata();
        metadata.setTaskId("task-1");
        metadata.setAgentId(agentId);
        metadata.setStatus(TaskStatus.SUSPENDED);
        metadata.setCurrentNodeId("n1");

        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setType(CheckpointType.APPROVAL);
        checkpoint.setAllowedActions(CheckpointType.APPROVAL.defaultActions());

        TurnScope turn = mock(TurnScope.class);
        when(turn.conversation()).thenReturn(new ConversationFence("conv-1", null, "task-1", 1L, null, 0L));
        when(turn.task()).thenReturn(Optional.of(metadata));
        when(turn.checkpoint()).thenReturn(Optional.of(checkpoint));

        EntryResult result = guard.guard(new TurnContext(request, turn));

        assertThat(result.forceContinue()).isTrue();
        assertThat(result.failedReply()).isNull();
        verify(agentProfiles, never()).requireSkillDigest(agentId);
        verify(agentProfiles, never()).listSkillDigest(agentId);
    }

    @Test
    void skillDigestUnavailable_throwsDependencyUnavailable() {
        Long agentId = 9L;
        AgentCatalogItemVo profile = new AgentCatalogItemVo();
        profile.setStatus(ResourceStatus.ACTIVE.name());
        when(agentProfiles.resolveProfile(agentId)).thenReturn(profile);
        when(agentProfiles.requireSkillDigest(agentId))
            .thenThrow(new BusinessException(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, "agent-studio"));

        TurnRequest request = new TurnRequest();
        request.setChannel("web");
        request.setAgentId(agentId);
        request.setSkillKey("cms.write");
        request.setUserInput("删分类");

        TurnScope turn = mock(TurnScope.class);
        when(turn.conversation()).thenReturn(new ConversationFence("conv-1", null, null, 1L, null, 0L));
        when(turn.task()).thenReturn(Optional.empty());
        when(turn.checkpoint()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.guard(new TurnContext(request, turn)))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getErrorCode())
            .isEqualTo(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE.code());
    }

    private static TurnContext context(CheckpointType type, String userInput) {
        TurnRequest request = new TurnRequest();
        request.setChannel("web");
        request.setUserInput(userInput);

        TaskMetadata metadata = new TaskMetadata();
        metadata.setTaskId("task-1");
        metadata.setStatus(TaskStatus.SUSPENDED);

        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setType(type);
        checkpoint.setAllowedActions(type.defaultActions());

        TurnScope turn = mock(TurnScope.class);
        when(turn.conversation()).thenReturn(new ConversationFence("conv-1", null, "task-1", 1L, null, 0L));
        when(turn.task()).thenReturn(Optional.of(metadata));
        when(turn.checkpoint()).thenReturn(Optional.of(checkpoint));
        return new TurnContext(request, turn);
    }

    private static SkillDigestVo digest(String skillKey) {
        SkillDigestVo digest = new SkillDigestVo();
        digest.setSkillKey(skillKey);
        digest.setSkillName(skillKey);
        return digest;
    }
}
