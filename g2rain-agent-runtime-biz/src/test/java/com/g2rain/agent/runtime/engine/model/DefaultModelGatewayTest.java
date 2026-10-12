package com.g2rain.agent.runtime.engine.model;

import com.g2rain.model.nexus.schema.ChatCompletionChoice;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.schema.ChatToolCall;
import com.g2rain.model.nexus.vo.ChatCompletionResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型调用异常判定。
 *
 * @author alpha
 * @since 2026/10/11
 */
class DefaultModelGatewayTest {

    private static ChatCompletionResponse response(String content, List<ChatToolCall> calls, String finishReason) {
        ChatMessage message = new ChatMessage();
        message.setContent(content);
        message.setToolCalls(calls);
        ChatCompletionChoice choice = new ChatCompletionChoice();
        choice.setMessage(message);
        choice.setFinishReason(finishReason);
        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setChoices(List.of(choice));
        return response;
    }

    @Test
    void noChoicesIsEmpty() {
        assertThat(DefaultModelGateway.abnormalOf(new ChatCompletionResponse())).isEqualTo(DefaultModelGateway.Abnormal.EMPTY);
    }

    @Test
    void blankContentWithoutToolCallsIsEmpty() {
        assertThat(DefaultModelGateway.abnormalOf(response("  ", List.of(), "stop"))).isEqualTo(DefaultModelGateway.Abnormal.EMPTY);
    }

    @Test
    void lengthFinishIsTruncated() {
        assertThat(DefaultModelGateway.abnormalOf(response("{\"mode\":", List.of(), "length")))
            .isEqualTo(DefaultModelGateway.Abnormal.LENGTH);
    }

    @Test
    void contentOrToolCallsIsNormal() {
        assertThat(DefaultModelGateway.abnormalOf(response("{}", List.of(), "stop"))).isNull();
        assertThat(DefaultModelGateway.abnormalOf(response(null, List.of(new ChatToolCall()), "tool_calls"))).isNull();
    }
}
