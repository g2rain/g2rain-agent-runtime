package com.g2rain.agent.runtime.engine.flow.plan;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.engine.model.ModelGateway;
import com.g2rain.model.nexus.enums.MessageRole;
import com.g2rain.model.nexus.schema.ChatCompletionChoice;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.vo.ChatCompletionResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SkillSelector} 用例 P7～P9。
 *
 * @author alpha
 * @since 2026/11/22
 */
class SkillSelectorTest {

    private static final List<SkillItem> WHITELIST = List.of(
        new SkillItem("a", "A"),
        new SkillItem("b", "B")
    );

    /** P7：明确空数组 → 空列表。 */
    @Test
    void p7_emptyArray_returnsEmpty() {
        assertThat(select("{\"candidateSkillKeys\":[]}")).isEmpty();
    }

    /** P8：无法解析 → 全量白名单。 */
    @Test
    void p8_unparsed_returnsWhitelist() {
        assertThat(select("not-json")).isEqualTo(WHITELIST);
    }

    /** P9：缺字段 → 全量白名单。 */
    @Test
    void p9_missingField_returnsWhitelist() {
        assertThat(select("{}")).isEqualTo(WHITELIST);
    }

    private static List<SkillItem> select(String content) {
        ModelGateway gateway = mock(ModelGateway.class);
        when(gateway.chat(any(), any(), any())).thenReturn(response(content));
        SkillSelector selector = new SkillSelector(gateway, new RuntimeProperties());
        TurnRequest request = new TurnRequest();
        TurnContext ctx = new TurnContext(request, mock(com.g2rain.agent.runtime.domain.state.TurnScope.class));
        return selector.select(ctx, WHITELIST, "goal");
    }

    private static ChatCompletionResponse response(String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(MessageRole.ASSISTANT);
        message.setContent(content);
        ChatCompletionChoice choice = new ChatCompletionChoice();
        choice.setMessage(message);
        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setChoices(List.of(choice));
        return response;
    }
}
