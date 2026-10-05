package com.g2rain.agent.runtime.domain.graph;


import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.entry.EntryResult;
import com.g2rain.agent.runtime.domain.execute.ExecutorResult;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 图调度一次请求期间使用的执行上下文。
 *
 * <p>它把当前会话视图、技能快照、入口决策和节点焦点组合在一起，供图调度器在一次
 * 请求内共享；持久化数据仍由 {@link TurnScope} 负责承载。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class GraphFrame {

    /**
     * 本轮可读写的任务视图；图、会话和对话数据均从该视图访问。
     */
    private final TurnScope turn;

    /**
     * 当前 Agent 可使用的技能快照；构造时复制，防止调用方之后修改列表。
     */
    private final List<Skill> skills;

    /**
     * 本轮用户原文。
     */
    private final @Nullable String userInput;

    /**
     * 当前焦点节点标识；可由任务元数据或入口恢复结果提供。
     */
    private final @Nullable String currentNodeId;

    /**
     * 本轮入口携带的恢复动作；没有恢复请求时为空。
     */
    private final @Nullable ResumeAction action;

    /**
     * 用于生成用户可见文案或模型上下文的 Agent 简介。
     */
    private final @Nullable String profileText;

    /**
     * 调度器主动停止图时保存的最终结果；为空表示尚未停止。
     */
    private @Nullable ExecutorResult halt;

    /**
     * 节点 finish 带回的完成说明；空闲成功收口时作为面向用户的文案。
     */
    private @Nullable String completionMessage;

    /**
     * 是否已经执行过焦点唤醒，避免同一次调度重复把挂起节点置为就绪。
     */
    private boolean wakeDone;

    /**
     * 根据本轮状态、技能和入口结论组装图调度上下文。
     *
     * <p>入口结果中的焦点和恢复动作优先于请求默认值；入口没有覆盖焦点时，才从任务元数据
     * 读取当前节点。技能列表按不可变副本保存。</p>
     *
     * @param turn          本轮视图
     * @param skills        本轮技能
     * @param userInput     用户原文
     * @param requestAction 请求上的恢复动作
     * @param entry         入口结论；可空
     * @param selection     Agent 人选；可空
     */
    public GraphFrame(TurnScope turn, @Nullable List<Skill> skills, @Nullable String userInput,
                      @Nullable ResumeAction requestAction, @Nullable EntryResult entry,
                      @Nullable AgentSelection selection) {
        // 入口结论上的恢复动作优先于请求默认值
        ResumeAction action = Objects.nonNull(entry) ? entry.effectiveAction() : requestAction;
        String currentNodeId = turn.task().map(TaskMetadata::getCurrentNodeId).orElse(null);
        // 入口续跑/换人结论上的焦点覆盖任务元数据
        if (Objects.nonNull(entry) && Strings.isNotBlank(entry.currentNodeId())) {
            currentNodeId = entry.currentNodeId();
        }

        this.turn = turn;
        this.skills = Objects.nonNull(skills) ? List.copyOf(skills) : List.of();
        this.userInput = userInput;
        this.currentNodeId = currentNodeId;
        this.action = action;
        this.profileText = Objects.nonNull(selection) ? selection.getProfileText() : null;
    }

    /**
     * @return 本轮视图
     */
    public TurnScope turn() {
        return turn;
    }

    /**
     * @return 任务图
     */
    public TaskGraph graph() {
        return turn.graph();
    }

    /**
     * @return 会话 id
     */
    public String conversationId() {
        return turn.conversation().conversationId();
    }

    /**
     * @return 任务 id；无任务为空串
     */
    public String taskId() {
        return turn.task().map(TaskMetadata::getTaskId).orElse("");
    }

    /**
     * @return 技能列表
     */
    public List<Skill> skills() {
        return skills;
    }

    /**
     * 按技能键查找本轮快照中的技能。
     *
     * @param skillKey 技能键；空白直接返回 {@code null}
     * @return 第一个匹配技能；未命中为 {@code null}
     */
    public @Nullable Skill findSkill(@Nullable String skillKey) {
        // 空白键无法定位，避免把空串当成合法 skillKey
        if (Strings.isBlank(skillKey)) {
            return null;
        }

        return skills.stream()
            .filter(s -> skillKey.equals(s.skillKey()))
            .findFirst()
            .orElse(null);
    }

    /**
     * @return 用户输入
     */
    public @Nullable String userInput() {
        return userInput;
    }

    /**
     * @return 焦点节点
     */
    public @Nullable String currentNodeId() {
        return currentNodeId;
    }

    /**
     * @return 恢复动作
     */
    public @Nullable ResumeAction action() {
        return action;
    }

    /**
     * @return Agent 简介文本，供用户可见文案或模型上下文；可空
     */
    public @Nullable String profileText() {
        return profileText;
    }

    /**
     * @return 是否已唤醒
     */
    public boolean wakeDone() {
        return wakeDone;
    }

    /**
     * 标记已唤醒。
     */
    public void markWakeDone() {
        this.wakeDone = true;
    }

    /**
     * @return 停图结果；尚未停图时为 {@code null}
     */
    public @Nullable ExecutorResult halt() {
        return halt;
    }

    /**
     * 记录停图结果。
     *
     * <p>该方法只保存调度结论，不会直接改变 {@link #turn()} 中的持久化状态。</p>
     *
     * @param result 停图回执
     */
    public void halt(ExecutorResult result) {
        this.halt = result;
    }

    /**
     * @return 完成说明；可空
     */
    public @Nullable String completionMessage() {
        return completionMessage;
    }

    /**
     * 记下节点 finish 的产出文案；空白不覆盖已有值。
     *
     * @param message 完成说明；空白忽略
     */
    public void completionMessage(@Nullable String message) {
        // 空白不覆盖已有完成说明，避免空 finish 冲掉节点产出
        if (Strings.isNotBlank(message)) {
            this.completionMessage = message.trim();
        }
    }

    /**
     * @return 是否应停止调度
     */
    public boolean terminal() {
        return Objects.nonNull(halt);
    }
}
