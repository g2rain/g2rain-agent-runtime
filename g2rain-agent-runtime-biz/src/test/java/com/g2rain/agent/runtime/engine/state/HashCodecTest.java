package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link HashCodec} 读写对称测试。
 *
 * @author alpha
 * @since 2026/11/16
 */
class HashCodecTest {

    private static final String JSON_LIKE_REQUEST = "{\"title\": \"G2rain 2.0\"}";

    private static final String BRACKET_GOAL = "[图片] 把这张图配到文章里";

    private static final String NUMERIC_MESSAGE = "42";

    @Test
    void toObject_shouldKeepFreeTextThatLooksLikeJson() {
        TaskMetadata metadata = new TaskMetadata();
        metadata.setTaskId("t1");
        metadata.setStatus(TaskStatus.RUNNING);
        metadata.setRequest(JSON_LIKE_REQUEST);
        metadata.setGoalSummary(BRACKET_GOAL);
        metadata.setFinalMessage(NUMERIC_MESSAGE);
        metadata.setVersion(3L);

        TaskMetadata loaded = Objects.requireNonNull(HashCodec.toObject(HashCodec.toHash(metadata), TaskMetadata.class));

        assertEquals(JSON_LIKE_REQUEST, loaded.getRequest());
        assertEquals(BRACKET_GOAL, loaded.getGoalSummary());
        assertEquals(NUMERIC_MESSAGE, loaded.getFinalMessage());
        assertEquals(TaskStatus.RUNNING, loaded.getStatus());
        assertEquals(3L, loaded.getVersion());
    }

    @Test
    void toObject_shouldReadFenceFieldsWrittenByRenewScript() {
        Map<String, String> hash = Map.of("version", "5", "lastTraceId", "\"trace-1\"", "updatedAt", "1700000000000");

        ConversationFence fence = Objects.requireNonNull(HashCodec.toObject(hash, ConversationFence.class));

        assertEquals(5L, fence.version());
        assertEquals("trace-1", fence.lastTraceId());
        assertEquals(1700000000000L, fence.updatedAt());
    }
}
