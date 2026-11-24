package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.task.EntityEntry;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 测试用 {@link DefaultTurnScope} 构造：附件上限取 {@link RuntimeProperties} 缺省值。
 *
 * @author alpha
 * @since 2026/11/24
 */
public final class TurnScopes {

    /**
     * 配置缺省的附件上限。
     */
    private static final RuntimeProperties.AttachmentLimits ATTACHMENT =
        new RuntimeProperties().getAttachment();

    private TurnScopes() {
    }

    /**
     * 用配置缺省附件上限构造本轮视图。
     *
     * @param organId       机构 id
     * @param userId        用户 id
     * @param traceId       本轮 traceId
     * @param fence         会话栅栏
     * @param dialogue      交互历史
     * @param records       任务记录
     * @param entities      实体
     * @param task          活跃任务；可空
     * @param graph         任务图
     * @param checkpoint    断点；可空
     * @param shared        任务共享值
     * @param prior         前情；可空
     * @param sessionShared 会话共享值
     * @param dialogueMax   交互历史保留条数
     * @return 本轮视图
     */
    public static DefaultTurnScope create(String organId, String userId, String traceId, ConversationFence fence,
                                   List<DialogueMessage> dialogue, List<TaskRecord> records,
                                   Map<String, EntityEntry> entities, @Nullable TaskMetadata task, TaskGraph graph,
                                   @Nullable TaskCheckpoint checkpoint, Map<String, Sourced<?>> shared,
                                   @Nullable Prior prior, Map<String, Sourced<?>> sessionShared, int dialogueMax) {
        return new DefaultTurnScope(organId, userId, traceId, fence, dialogue, records, entities, task, graph,
            checkpoint, shared, prior, sessionShared, dialogueMax,
            ATTACHMENT.getMaxPerTurn(), ATTACHMENT.getMaxChars());
    }
}
