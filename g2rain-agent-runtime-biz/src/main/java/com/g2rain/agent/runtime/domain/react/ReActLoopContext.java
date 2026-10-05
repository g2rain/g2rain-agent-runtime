package com.g2rain.agent.runtime.domain.react;


import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.common.utils.Strings;
import com.g2rain.model.nexus.schema.ChatMessage;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Objects;

/**
 * ReAct 循环上下文：跑单节点推理-行动时的只读输入包。
 *
 * <p>从图帧取出任务/节点/技能，并带上投影给模型的历史消息与本轮工具回调。
 * 不保存循环中间态；挂起与产出写在 {@link com.g2rain.agent.runtime.domain.tool.ToolExecutionContext}。</p>
 *
 * @param frame           图帧
 * @param node            当前执行节点
 * @param scope           当前节点视图
 * @param historyMessages 供模型参考的历史消息（交互历史与工具观察的投影）
 * @param toolCallbacks   本轮可用的工具回调列表
 * @author alpha
 * @since 2026/8/21
 */
public record ReActLoopContext(GraphFrame frame, TaskNode node, NodeScope scope, List<ChatMessage> historyMessages,
                               List<ToolCallback> toolCallbacks) {

    /**
     * 当前任务的唯一标识。
     *
     * @return 任务 id；无任务时可能为空串
     */
    public String taskId() {
        return frame.taskId();
    }

    /**
     * 当前任务图节点的唯一标识。
     *
     * @return 节点 id
     */
    public String nodeId() {
        return node.getNodeId();
    }

    /**
     * 当前会话的唯一标识，用于把工具调用和状态变更归属到正确的会话。
     *
     * @return 会话 id
     */
    public String conversationId() {
        return frame.conversationId();
    }

    /**
     * 当前任务图。
     *
     * @return 任务图
     */
    public TaskGraph graph() {
        return frame.graph();
    }

    /**
     * 当前节点依赖的前置节点 id 列表。
     *
     * @return 依赖 id；无依赖时为空列表
     */
    public List<String> dependsOn() {
        return node.getDependsOn();
    }

    /**
     * 技能手册文本；无技能则为 {@code null}。
     *
     * @return 手册正文；可空
     */
    public @Nullable String skillManual() {
        Skill skill = skill();
        return Objects.isNull(skill) ? null : skill.skillManual();
    }

    /**
     * 技能列表中标记为可委派的技能键列表。
     *
     * @return 可委派 skillKey；没有则为空列表
     */
    public List<String> delegatableSkillKeys() {
        return frame.skills().stream()
            .filter(s -> Objects.nonNull(s.contract()))
            .filter(s -> s.contract().isDelegatable())
            .map(Skill::skillKey)
            .filter(Strings::isNotBlank)
            .toList();
    }

    /**
     * 按当前节点 skillKey 在技能列表中查找技能。
     *
     * @return 匹配技能；找不到为 {@code null}
     */
    private @Nullable Skill skill() {
        return frame.findSkill(node.getSkillKey());
    }

    /**
     * 当前技能的契约；无技能则为 {@code null}。
     *
     * @return 技能契约；可空
     */
    public @Nullable SkillContract contract() {
        Skill skill = skill();
        return Objects.isNull(skill) ? null : skill.contract();
    }
}
