package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.task.ClosedTask;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.NodeSpace;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.state.repo.CallRepository;
import com.g2rain.agent.runtime.engine.state.repo.ConversationRepository;
import com.g2rain.agent.runtime.engine.state.repo.GraphRepository;
import com.g2rain.agent.runtime.engine.state.repo.ProgressRepository;
import com.g2rain.agent.runtime.engine.state.repo.TaskRepository;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 轮末冷库落库编排：在一个事务里按顺序调用各仓储。
 *
 * <p>具体表的读写在 {@code engine.state.repo} 的仓储中；本类只负责本轮要写哪些数据、按什么顺序写。</p>
 *
 * @author alpha
 * @since 2026/9/10
 */
@Component
@RequiredArgsConstructor
public class RuntimeStatePersistence {

    /**
     * 会话共享值序列化。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 会话仓储。
     */
    private final ConversationRepository conversations;

    /**
     * 任务仓储。
     */
    private final TaskRepository tasks;

    /**
     * 图仓储。
     */
    private final GraphRepository graphs;

    /**
     * 工具调用仓储。
     */
    private final CallRepository calls;

    /**
     * 执行过程步骤仓储。
     */
    private final ProgressRepository progress;

    /**
     * 轮末冷库事务：首句推进会话 {@code state_version}，再写任务 / 图节点空间 / 证据头 / 检查点 / 消息。
     * <p>CAS 影响 0 行则抛 {@link RuntimeErrorCode#STATE_FENCED}，整事务回滚。</p>
     *
     * @param scope 本轮视图
     */
    @Transactional(rollbackFor = Exception.class)
    public void persistTurn(DefaultTurnScope scope) {
        long stateVersion = scope.conversation().version();
        String conversationId = scope.conversation().conversationId();
        boolean terminal = scope.task().map(task -> TaskStatus.isTerminal(task.getStatus())).orElse(false);
        String activeTaskId = terminal ? null : scope.task().map(TaskMetadata::getTaskId).orElse(scope.conversation().activeTaskId());
        String sessionShared = JSON.obj2str(scope.sessionShared());
        if (conversations.advanceVersion(conversationId, stateVersion, sessionShared, activeTaskId) <= 0) {
            if (Objects.isNull(conversations.find(conversationId))) {
                throw new BusinessException(RuntimeErrorCode.STATE_WRITE_FAILED);
            }
            throw new BusinessException(RuntimeErrorCode.STATE_FENCED);
        }

        scope.closedTasks().forEach(closed -> persistClosed(closed, scope, stateVersion));
        scope.task().ifPresent(task -> {
            TaskRecord record = terminal ? recordOf(task, scope) : null;
            if (tasks.save(task, scope.shared(), scope.prior().orElse(null), stateVersion, record)) {
                conversations.bindTask(task.getSourceMessageId(), task.getTaskId());
            }
            graphs.save(task.getTaskId(), scope.graph(), spacesOf(scope.nodes()));
            calls.syncHeads(headsOf(scope.nodes()));
            scope.lastResumeAction().ifPresent(action -> tasks.consumeCheckpoint(task.getTaskId(), action,
                scope.lastResumeMessageId().orElse("")));
            scope.checkpoint().ifPresent(checkpoint -> {
                if (tasks.suspend(task.getTaskId(), checkpoint, task)) {
                    conversations.bindTask(task.getSourceMessageId(), task.getTaskId());
                }
            });
        });

        scope.pendingPersistDialogue().forEach(message -> {
            if (Objects.equals(message.getRole(), DialogueRole.USER)) {
                // 用户消息在 begin 时已 insert，避免轮末重复
                return;
            }
            conversations.insertMessage(conversationId, message.getTaskId(), message, null);
        });

        // 本轮执行步骤与消息同事务落库，历史接口按 traceId 关联到最终回复
        progress.insert(conversationId, scope.traceId(), scope.progress().pending());
    }

    /**
     * 被顶替的旧任务：写终态行与图壳。
     *
     * @param closed       已关闭任务快照
     * @param scope        本轮视图
     * @param stateVersion 本轮栅栏版本
     */
    private void persistClosed(ClosedTask closed, DefaultTurnScope scope, long stateVersion) {
        TaskMetadata task = closed.metadata();
        if (tasks.save(task, closed.shared(), closed.prior(), stateVersion, recordOf(task, scope))) {
            conversations.bindTask(task.getSourceMessageId(), task.getTaskId());
        }
        graphs.save(task.getTaskId(), closed.graph(), closed.spaces());
    }

    /**
     * 任务结束时留在会话上的记录。
     *
     * @param task  任务元数据
     * @param scope 本轮视图
     * @return 任务记录
     */
    private static TaskRecord recordOf(TaskMetadata task, DefaultTurnScope scope) {
        return new TaskRecord(task.getTaskId(), Objects.toString(task.getAgentId(), ""),
            task.getGoalSummary(), task.getStatus(), task.getFinalMessage(), scope.entities(), List.of(),
            System.currentTimeMillis());
    }

    /**
     * 把已打开节点视图收成节点空间。
     *
     * @param opened 本轮已打开节点
     * @return {@code nodeId →} 节点空间
     */
    private static Map<String, NodeSpace> spacesOf(Map<String, DefaultNodeScope> opened) {
        Map<String, NodeSpace> spaces = new LinkedHashMap<>();
        opened.forEach((id, node) -> spaces.put(id, new NodeSpace(
            node.inputs(), node.outputs(), node.state(), node.rounds(), node.tokens(), node.actions()
        )));
        return spaces;
    }

    /**
     * 已打开节点上的全部证据头。
     *
     * @param opened 本轮已打开节点
     * @return 证据头
     */
    private static List<EvidenceHead> headsOf(Map<String, DefaultNodeScope> opened) {
        return opened.values().stream().flatMap(node -> node.evidence().stream()).toList();
    }
}
