package com.g2rain.agent.runtime.engine.flow.graph;


import com.g2rain.agent.runtime.domain.execute.ExecutorResult;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.common.utils.Collections;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * GraphEffect（图副作用）：本拍归约后、待应用到帧/图上的动作。
 * <p>
 * 含：发事件、保存挂起、清除挂起、停图挂回执。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public sealed interface GraphEffect {

    /**
     * 发布一条过程提示。
     *
     * @param nodeId 相关节点 ID，仅用于日志定位
     * @param text   提示文案
     */
    record Publish(String nodeId, String text) implements GraphEffect {
    }

    /**
     * 保存任务级挂起检查点（追问/审批等）。
     *
     * @param nodeId         焦点节点 ID
     * @param checkpointType 检查点类型；可空则按追问
     * @param question       面向用户的追问/说明；可空
     * @param allowedActions 允许的恢复动作
     * @param pendingAction  待批动作；可空
     * @param expectedFields 仍缺的参数路径；可空
     * @param suggestions    ASK 快捷气泡；可空
     */
    record SaveSuspend(String nodeId, @Nullable CheckpointType checkpointType, @Nullable String question,
                       List<ResumeAction> allowedActions, @Nullable PendingAction pendingAction,
                       @Nullable List<String> expectedFields,
                       @Nullable List<String> suggestions) implements GraphEffect {

        /**
         * 便捷构造：allowedActions 由检查点类型默认动作填充。
         *
         * @param nodeId         焦点节点 ID
         * @param checkpointType 检查点类型；可空则按追问
         * @param question       面向用户的说明；可空
         * @param pendingAction  待批动作；可空
         * @param expectedFields 仍缺的参数路径；可空
         * @param suggestions    ASK 快捷气泡；可空
         */
        public SaveSuspend(String nodeId, @Nullable CheckpointType checkpointType, @Nullable String question,
                           @Nullable PendingAction pendingAction,
                           @Nullable List<String> expectedFields,
                           @Nullable List<String> suggestions) {
            CheckpointType type = Objects.requireNonNullElse(checkpointType, CheckpointType.FOLLOW_UP);
            this(nodeId, type, question, type.defaultActions(), pendingAction, expectedFields, suggestions);
        }

        /**
         * 转成任务级 {@link TaskCheckpoint}（协议壳，不含 ReAct ChatMessage）。
         *
         * @return 检查点
         */
        public TaskCheckpoint toCheckpoint() {
            CheckpointType type = Objects.requireNonNullElse(checkpointType, CheckpointType.FOLLOW_UP);
            TaskCheckpoint checkpoint = new TaskCheckpoint();
            checkpoint.setCheckpointId(RuntimeUtils.uuid());
            checkpoint.setType(type);
            checkpoint.setQuestion(question);
            checkpoint.setAllowedActions(new ArrayList<>(allowedActions));
            checkpoint.setPendingAction(pendingAction);

            if (Collections.isNotEmpty(suggestions)) {
                checkpoint.setSuggestions(new ArrayList<>(suggestions));
            }

            if (Collections.isNotEmpty(expectedFields)) {
                checkpoint.setExpectedFields(new ArrayList<>(expectedFields));
            }

            return checkpoint;
        }
    }

    /**
     * 清除任务级挂起检查点。
     *
     * @param nodeId 节点 ID（语义保留；清检查点按 task）
     */
    record ClearSuspend(String nodeId) implements GraphEffect {

    }

    /**
     * 停图：把任务级执行回执挂到帧上，结束主循环。
     *
     * @param result 执行回执
     */
    record Halt(ExecutorResult result) implements GraphEffect {

    }
}
