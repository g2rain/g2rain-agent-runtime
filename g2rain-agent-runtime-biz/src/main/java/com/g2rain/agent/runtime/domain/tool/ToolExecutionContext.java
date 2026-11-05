package com.g2rain.agent.runtime.domain.tool;


import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * ToolExecutionContext（工具执行上下文）：单节点工具调用期间的可变运行时状态。
 * <p>
 * 持有图帧、节点、技能列表与当前技能，并记录节点状态、产出、追问、检查点类型与
 * 指纹去重等。内置工具与拦截器通过本对象读契约、写挂起/完成/失败结局。
 *
 * @author alpha
 * @since 2026/8/21
 */
@Getter
public final class ToolExecutionContext {

    /**
     * 图执行帧，提供会话、任务、节点和可用技能的统一访问入口。
     */
    private final GraphFrame frame;

    /**
     * 当前正在执行的任务图节点。
     */
    private final TaskNode node;

    /**
     * 本轮加载的技能列表快照，避免执行过程中重复查询目录。
     */
    private final List<Skill> skills;

    /**
     * 按节点 skillKey 解析出的当前技能；未命中可为 {@code null}。
     */
    private final @Nullable Skill skill;

    /**
     * 当前节点允许委派的技能键集合，在构造上下文时预先计算。
     */
    private final List<String> delegatableSkillKeys;

    /**
     * 节点当前状态；上下文创建后默认为 {@code RUNNING}，由控制工具更新。
     */
    private NodeStatus nodeStatus = NodeStatus.RUNNING;

    /**
     * 节点完成时的产出对象；未完成时为 {@code null}。
     */
    private @Nullable Object output;

    /**
     * 展示给用户的追问或审批摘要；节点挂起时写入。
     */
    private @Nullable String question;

    /**
     * 追问挂起时仍缺失的参数路径，用于恢复时绑定用户补充内容。
     */
    private List<String> missingParameters = List.of();

    /**
     * ASK 快捷气泡文案（下发给客户端）；可空。
     */
    private List<String> suggestions = List.of();

    /**
     * 挂起检查点类型（追问 / 审批）；非挂起时为 {@code null}。
     */
    private @Nullable CheckpointType checkpointType;

    /**
     * 节点失败原因；节点进入失败状态时写入。
     */
    private @Nullable String failReason;

    /**
     * 审批挂起时等待用户确认的动作；非审批场景为空。
     */
    private @Nullable PendingAction pendingAction;

    /**
     * 最近一次「完成被拒」的原因（节点仍可继续跑）；可空。
     */
    private @Nullable String finishRejectedReason;

    /**
     * 最近一次工具调用的指纹；没有历史调用时为空。
     */
    private @Nullable String lastFingerprint;

    /**
     * 当前指纹连续成功的调用次数，用于限制无进展重复调用。
     */
    private int fingerprintRepeated;

    /**
     * 当前节点的只读状态视图，供工具和拦截器读取。
     */
    private final NodeScope scope;

    /**
     * 用户确认后允许继续执行写工具时为 {@code true}。
     */
    private boolean approvedWrite;

    /**
     * 由帧、节点与当前节点视图构造上下文。
     *
     * @param frame 图帧
     * @param node  当前节点
     * @param scope 当前节点视图
     */
    public ToolExecutionContext(GraphFrame frame, TaskNode node, NodeScope scope) {
        this.frame = Objects.requireNonNull(frame);
        this.node = Objects.requireNonNull(node);
        this.scope = Objects.requireNonNull(scope);
        this.skills = frame.skills();
        this.skill = frame.findSkill(node.getSkillKey());
        // 构造时拍下可委派技能键，调用期只读，避免重复扫契约
        this.delegatableSkillKeys = skills.stream()
            .filter(s -> Objects.nonNull(s.contract()))
            .filter(s -> s.contract().isDelegatable())
            .map(Skill::skillKey)
            .filter(Strings::isNotBlank)
            .toList();
    }

    /**
     * @return 当前节点视图
     */
    public NodeScope scope() {
        return scope;
    }

    /**
     * 标记当前调用已通过写工具的用户确认检查。
     */
    public void markApprovedWrite() {
        this.approvedWrite = true;
    }

    /**
     * @return 是否已确认写工具
     */
    public boolean approvedWrite() {
        return approvedWrite;
    }

    /**
     * 当前任务的唯一标识。
     *
     * @return 任务 id
     */
    public String getTaskId() {
        return frame.taskId();
    }

    /**
     * 当前任务图节点的唯一标识。
     *
     * @return 节点 id
     */
    public String getNodeId() {
        return node.getNodeId();
    }

    /**
     * 当前会话的唯一标识。
     *
     * @return 会话 id
     */
    public String getConversationId() {
        return frame.conversationId();
    }

    /**
     * 当前节点绑定的技能键；可空。
     *
     * @return 技能键
     */
    public @Nullable String getSkillKey() {
        return node.getSkillKey();
    }

    /**
     * 当前技能契约；无技能则为 {@code null}。
     *
     * @return 技能契约；可空
     */
    public @Nullable SkillContract contract() {
        return Objects.isNull(skill) ? null : skill.contract();
    }

    /**
     * 返回当前任务图的可变运行视图。
     *
     * @return 当前请求正在执行的任务图
     */
    public TaskGraph getGraph() {
        return frame.graph();
    }

    /**
     * 节点完成目标：要完成的写工具注册名（空白归一为 null）。
     *
     * @return 目标工具名；可空
     */
    public @Nullable String getTarget() {
        return Strings.blankToNull(node.getTarget());
    }

    /**
     * 节点尚无完成目标时，把本次实际发起的写调用记为目标；之后 finish 须等它成功。
     * <p>该写工具已是其他未结束节点的目标时不认领，交写范围门拒绝。</p>
     *
     * @param toolName 本次调用的工具注册名
     * @return 本次记为目标时为 {@code true}
     */
    public boolean claimTarget(String toolName) {
        if (Strings.isNotBlank(node.getTarget()) || Objects.isNull(skill) || !skill.writeTools().contains(toolName)
            || Objects.nonNull(getGraph().targetOwner(toolName, node.getNodeId()))) {
            return false;
        }

        node.setTarget(toolName);
        node.setTargetClaimed(true);
        return true;
    }

    /**
     * 当前技能是否有可作为完成目标的写工具。
     *
     * @return 有写工具时为 {@code true}
     */
    public boolean hasWriteTools() {
        return Objects.nonNull(skill) && !skill.writeTools().isEmpty();
    }

    /**
     * 当前技能是否声明了至少一个业务工具的注册名称。
     *
     * @return {@code true} 表示期望调用业务工具
     */
    public boolean expectsBusinessTools() {
        return Objects.nonNull(skill) && !skill.toolNames().isEmpty();
    }

    /**
     * 节点是否已离开 RUNNING（完成 / 挂起 / 失败 / 等待委派等）。
     *
     * @return {@code true} 表示已退出运行中
     */
    public boolean hasExited() {
        return nodeStatus != NodeStatus.RUNNING;
    }

    /**
     * 清除「完成被拒」原因，允许再次尝试 finish。
     */
    public void clearFinishRejected() {
        this.finishRejectedReason = null;
    }

    /**
     * 转为追问挂起：写 SUSPENDED + FOLLOW_UP，并记录问题、缺参与快捷建议。
     *
     * @param question          追问文案
     * @param missingParameters 缺失参数路径
     * @param suggestions       快捷气泡文案；可空
     */
    public void applyFollowUp(String question, List<String> missingParameters, @Nullable List<String> suggestions) {
        this.nodeStatus = NodeStatus.SUSPENDED;
        this.checkpointType = CheckpointType.FOLLOW_UP;
        this.question = question;
        this.missingParameters = List.copyOf(missingParameters);
        this.suggestions = Objects.isNull(suggestions) ? List.of() : List.copyOf(suggestions);
    }

    /**
     * 转为审批挂起：写 SUSPENDED + APPROVAL，并记录摘要与待确认动作。
     *
     * @param summary       审批摘要
     * @param pendingAction 待确认动作
     */
    public void applyApproval(String summary, PendingAction pendingAction) {
        this.nodeStatus = NodeStatus.SUSPENDED;
        this.checkpointType = CheckpointType.APPROVAL;
        this.question = summary;
        this.pendingAction = pendingAction;
    }

    /**
     * 标记节点完成：写 DONE 与产出，并清挂起/完成拒绝原因。
     *
     * @param output 节点产出
     */
    public void applyFinish(Object output) {
        this.nodeStatus = NodeStatus.DONE;
        this.output = output;
        this.checkpointType = null;
        this.finishRejectedReason = null;
    }

    /**
     * 记录一次完成被拒（节点仍可继续），不改变 nodeStatus。
     *
     * @param reason 拒绝原因
     */
    public void applyFinishRejected(String reason) {
        this.finishRejectedReason = reason;
    }

    /**
     * 标记节点失败：写 FAILED 与原因，并清挂起类型。
     *
     * @param reason 失败原因
     */
    public void applyFail(String reason) {
        this.nodeStatus = NodeStatus.FAILED;
        this.failReason = reason;
        this.checkpointType = null;
    }

    /**
     * 标记节点等待委派子技能：写 WAITING_DELEGATE，并清挂起类型。
     */
    public void applyWaitingDelegate() {
        this.nodeStatus = NodeStatus.WAITING_DELEGATE;
        this.checkpointType = null;
    }

    /**
     * 预览连续相同调用指纹的处理决策，不改计数。
     *
     * <p>指纹与上次不同视为 {@link FingerprintDecision#OK}；相同则按 {@code tripLimit}（至少 2）
     * 预判下一轮是放行、警告还是熔断。拦截器据此决定是否调用工具。</p>
     *
     * @param fingerprint 当前调用指纹
     * @param tripLimit   连续相同次数上限
     * @return OK / WARN / TRIP
     */
    public FingerprintDecision peekFingerprintDecision(String fingerprint, int tripLimit) {
        int limit = Math.max(2, tripLimit);
        // 指纹变了视为新调用，连续计数不在预览路径上递增
        if (!Objects.equals(fingerprint, lastFingerprint)) {
            return FingerprintDecision.OK;
        }

        int nextRound = fingerprintRepeated + 1;
        // 下一轮将触及上限：熔断，避免无进展重复调用
        if (nextRound >= limit) {
            return FingerprintDecision.TRIP;
        }

        if (nextRound == limit - 1) {
            return FingerprintDecision.WARN;
        }

        return FingerprintDecision.OK;
    }

    /**
     * 工具调用成功后累加当前指纹的连续次数。
     *
     * <p>指纹变化时重置计数再记 1；相同则只递增。须在实际成功之后调用，与 {@link #peekFingerprintDecision} 成对。</p>
     *
     * @param fingerprint 本次成功调用的指纹
     */
    public void recordFingerprint(String fingerprint) {
        // 换指纹则清零后再累加，保证计数只表示连续相同成功
        if (!Objects.equals(fingerprint, lastFingerprint)) {
            lastFingerprint = fingerprint;
            fingerprintRepeated = 0;
        }

        fingerprintRepeated++;
    }

    /**
     * 根据工具名和规范化参数生成稳定的调用指纹。
     *
     * <p>参数空白按 {@code {}} 解析；JSON 无法解析时退回带纳秒的失败指纹，避免误判为相同调用。</p>
     *
     * @param toolName  工具注册名
     * @param arguments 参数 JSON；可空或空白
     * @return {@code 工具名|argsHash}；解析失败为 {@code 工具名|fp-fail-<nano>}
     * @apiNote {@code ToolExecutionContext.fingerprintOf("query_order", "{\"id\":\"1\"}")}
     */
    public static String fingerprintOf(String toolName, String arguments) {
        try {
            String args = Strings.defaultIfBlank(arguments, "{}");
            return toolName + "|" + RuntimeUtils.argsHash(JsonCodecFactory.instance().str2node(args));
        } catch (RuntimeException _) {
            // 解析失败必须换新指纹，防止坏 JSON 被当成同一调用
            return toolName + "|fp-fail-" + System.nanoTime();
        }
    }

    /**
     * 连续相同调用指纹的处理决策。
     */
    public enum FingerprintDecision {
        /**
         * 放行：指纹变化或尚未接近上限。
         */
        OK,

        /**
         * 警告：再成功一次相同指纹将熔断。
         */
        WARN,

        /**
         * 熔断：连续次数已达上限，拦截器应阻断调用。
         */
        TRIP
    }
}
