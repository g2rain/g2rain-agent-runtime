package com.g2rain.agent.runtime.engine.flow.plan;

import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.engine.model.ModelGateway;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.model.nexus.dto.ChatCompletionRequest;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.enums.MessageRole;
import com.g2rain.model.nexus.schema.ChatCompletionChoice;
import com.g2rain.model.nexus.vo.ChatCompletionResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlanDrafter} 前情任务用例 P1、P2。
 *
 * @author alpha
 * @since 2026/11/22
 */
class PlanDrafterTest {

    /** P1：useContinuity 为真 → priorTasks 两项，新的在前。 */
    @Test
    void p1_priorTasks_newestFirstWhenContinuity() {
        ModelGateway gateway = mock(ModelGateway.class);
        when(gateway.chat(any(), any(), any())).thenReturn(planResponse());
        PlanDrafter drafter = new PlanDrafter(gateway);

        TaskRecord older = record("t-old", "旧目标", "旧结论", "旧原话");
        TaskRecord newer = record("t-new", "新目标", "新结论", "新原话");
        TurnContext ctx = context(TurnDecision.startFollowup(),
            new Prior(List.of(older, newer), Map.of(), List.of()));

        drafter.draft(ctx, List.of(new SkillItem("s1", "desc")), Map.of("s1", skill()), "goal");

        JsonNode priorTasks = captureUserJson(gateway).get("priorTasks");
        assertThat(priorTasks).isNotNull().hasSize(2);
        assertThat(priorTasks.get(0).get("goal").asString()).isEqualTo("新目标");
        assertThat(priorTasks.get(0).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(priorTasks.get(0).get("summary").asString()).isEqualTo("新结论");
        assertThat(priorTasks.get(0).get("request").asString()).isEqualTo("新原话");
        assertThat(priorTasks.get(1).get("goal").asString()).isEqualTo("旧目标");
    }

    /** P2：useContinuity 为假 → 序列化没有 priorTasks。 */
    @Test
    void p2_priorTasks_absentWithoutContinuity() {
        ModelGateway gateway = mock(ModelGateway.class);
        when(gateway.chat(any(), any(), any())).thenReturn(planResponse());
        PlanDrafter drafter = new PlanDrafter(gateway);

        TaskRecord record = record("t1", "目标", "结论", "原话");
        TurnContext ctx = context(TurnDecision.startFresh(),
            new Prior(List.of(record), Map.of(), List.of()));

        drafter.draft(ctx, List.of(new SkillItem("s1", "desc")), Map.of("s1", skill()), "goal");

        assertThat(captureUserJson(gateway).get("priorTasks")).isNull();
    }

    private static JsonNode captureUserJson(ModelGateway gateway) {
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(gateway).chat(any(), any(), captor.capture());
        String user = captor.getValue().getMessages().get(1).getContent();
        int from = user.indexOf("【输入数据】") + "【输入数据】".length();
        int to = user.indexOf("【输出】");
        return JsonCodecFactory.instance().str2node(user.substring(from, to).trim());
    }

    private static TurnContext context(TurnDecision decision, Prior prior) {
        TurnRequest request = new TurnRequest();
        request.setUserInput("办一件事");
        TurnScope turn = mock(TurnScope.class);
        when(turn.prior()).thenReturn(Optional.of(prior));
        TurnContext ctx = new TurnContext(request, turn);
        ctx.setDecision(decision);
        return ctx;
    }

    private static TaskRecord record(String taskId, String goal, String summary, String request) {
        return new TaskRecord(taskId, "1", goal, TaskStatus.SUCCEEDED, summary, request, null, null,
            List.of(), List.of(), 1L);
    }

    private static Skill skill() {
        return new Skill("s1", "s1", List.of(), "desc", "manual", new SkillContract(), null);
    }

    private static ChatCompletionResponse planResponse() {
        ChatMessage message = new ChatMessage();
        message.setRole(MessageRole.ASSISTANT);
        message.setContent("{\"nodes\":[{\"nodeId\":\"n1\",\"title\":\"做\",\"objective\":\"做完\",\"skillKey\":\"s1\",\"dependsOn\":[]}]}");
        ChatCompletionChoice choice = new ChatCompletionChoice();
        choice.setMessage(message);
        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setChoices(List.of(choice));
        return response;
    }
}
