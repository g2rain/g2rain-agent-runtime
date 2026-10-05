package com.g2rain.agent.runtime.engine.flow.graph;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.ArgSpec;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.hydrate.ValueRef;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.event.EventPublisher;
import com.g2rain.agent.runtime.event.TurnEvents;
import com.g2rain.agent.runtime.support.ArgsBrief;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * DefaultEffectHandler：默认副作用应用器。
 * <p>
 * 逐项处理：发布事件、保存/清除挂起（Facade checkpoint）、把停图结论写入帧。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultEffectHandler implements EffectHandler {

    /**
     * 解析待批参数。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 本轮事件发布器。
     */
    private final EventPublisher eventPublisher;

    /**
     * 待批参数摘要上限。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 按顺序应用副作用；列表空则直接返回。
     *
     * @param frame   当前图执行帧
     * @param effects 副作用列表
     */
    @Override
    public void apply(GraphFrame frame, List<GraphEffect> effects) {
        if (Collections.isEmpty(effects)) {
            // 无副作用：本拍只推进了调度态（如 WakeFocus）
            return;
        }

        log.info("[EffectHandler] apply taskId={} effects={}",
            frame.taskId(), effects
        );

        effects.forEach(effect -> {
            switch (effect) {
                case GraphEffect.Publish p -> publish(frame, p);
                case GraphEffect.SaveSuspend s -> saveSuspend(frame, s);
                case GraphEffect.ClearSuspend c -> clearSuspend(frame, c);
                case GraphEffect.Halt h -> halt(frame, h);
            }
        });
    }

    /**
     * 发布一条过程提示。
     *
     * @param frame   当前图执行帧，提供本轮视图
     * @param publish 过程提示副作用
     */
    private void publish(GraphFrame frame, GraphEffect.Publish publish) {
        eventPublisher.publish(TurnEvents.progress(frame.turn(), publish.text()));
    }

    /**
     * 挂起：写 {@link com.g2rain.agent.runtime.domain.state.TurnScope#suspend}，不整图 saveGraph。
     *
     * <p>节点找不到则什么也不写。审批挂起时把待执行参数拼进问题，
     * 让用户确认的正是即将执行的那组参数；结果帧由收尾统一下发。</p>
     *
     * @param frame 当前图执行帧
     * @param save  挂起副作用，含节点、检查点类型和待处理动作
     */
    private void saveSuspend(GraphFrame frame, GraphEffect.SaveSuspend save) {
        TaskNode node = nodeOf(save.nodeId(), frame.graph());
        if (Objects.isNull(node)) {
            return;
        }

        TaskCheckpoint checkpoint = save.toCheckpoint();
        if (checkpoint.getType() == CheckpointType.APPROVAL) {
            String detail = approvalDetail(frame, save.nodeId(), checkpoint.getPendingAction());
            if (Strings.isNotBlank(detail)) {
                checkpoint.setQuestion(Strings.isBlank(checkpoint.getQuestion())
                    ? detail : checkpoint.getQuestion().trim() + "\n" + detail);
            }
        }
        frame.turn().suspend(checkpoint);
        frame.turn().focus(save.nodeId());
    }

    /**
     * 待批参数的可读摘要：脱敏后按「标签：值」拼接，平台注入的参数不展示。
     *
     * @param frame   当前图执行帧，用于取技能契约
     * @param nodeId  挂起节点 id
     * @param pending 待批准的写动作；为空或没有入参时不生成摘要
     * @return 如「文章标题：G2rain 2.0，内容空间标识：3」；没有可展示参数时为 {@code null}
     */
    private @Nullable String approvalDetail(GraphFrame frame, String nodeId, @Nullable PendingAction pending) {
        if (Objects.isNull(pending) || Strings.isBlank(pending.args())) {
            return null;
        }

        ToolSpec spec = toolSpec(frame, nodeId, pending.toolName());
        JsonNode brief = JSON.str2node(ArgsBrief.of(JSON.str2node(pending.args()), spec,
            runtimeProperties.getReact().getBriefMaxChars()));
        if (Objects.isNull(brief) || !brief.isObject()) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : brief.properties()) {
            ArgSpec arg = Objects.isNull(spec) ? null : spec.getArgs().get(field.getKey());
            if (isPlatform(arg)) {
                continue;
            }

            JsonNode value = field.getValue();
            String label = Objects.nonNull(arg) && Strings.isNotBlank(arg.getLabel()) ? arg.getLabel() : field.getKey();
            parts.add(label + "：" + (value.isValueNode() ? value.asString() : JSON.obj2str(value)));
        }

        return parts.isEmpty() ? null : String.join("，", parts);
    }

    private static boolean isPlatform(@Nullable ArgSpec arg) {
        return Objects.nonNull(arg) && Objects.nonNull(arg.getValueRef())
            && arg.getValueRef().bag() == ValueRef.Bag.PLATFORM;
    }

    /**
     * 按节点技能契约查找工具规格，找不到再扫本轮全部技能。
     *
     * @param frame    当前图执行帧
     * @param nodeId   挂起节点 id
     * @param toolName 待批准工具名；空白则不查
     * @return 用于脱敏的工具规格；找不到时为 {@code null}，摘要只做长度裁剪
     */
    private static @Nullable ToolSpec toolSpec(GraphFrame frame, String nodeId, @Nullable String toolName) {
        if (Strings.isBlank(toolName)) {
            return null;
        }

        TaskNode node = nodeOf(nodeId, frame.graph());
        if (Objects.nonNull(node)) {
            Skill skill = frame.findSkill(node.getSkillKey());
            if (Objects.nonNull(skill) && Objects.nonNull(skill.contract())) {
                ToolSpec spec = skill.contract().tool(toolName);
                if (Objects.nonNull(spec)) {
                    return spec;
                }
            }
        }

        for (Skill skill : frame.skills()) {
            if (Objects.isNull(skill.contract())) {
                continue;
            }

            ToolSpec spec = skill.contract().tool(toolName);
            if (Objects.nonNull(spec)) {
                return spec;
            }
        }

        return null;
    }

    /**
     * 清挂起：写 {@code turn.resume}。
     *
     * <p>恢复动作用帧上的用户选择，缺省按 {@link ResumeAction#REPLY}。
     * 消息 id 取本轮最后一条 USER 交互历史，便于把恢复和那条输入对齐。</p>
     *
     * @param frame 当前图执行帧
     * @param clear 清挂起副作用；本方法不读其字段，只作为分发入口
     */
    private void clearSuspend(GraphFrame frame, GraphEffect.ClearSuspend clear) {
        ResumeAction action = Objects.requireNonNullElse(frame.action(), ResumeAction.REPLY);
        String messageId = frame.turn().allDialogue().stream()
            .filter(message -> message.getRole() == DialogueRole.USER
                && Objects.equals(message.getTraceId(), frame.turn().traceId()))
            .reduce((_, second) -> second)
            .map(DialogueMessage::getMessageId)
            .orElse("");
        frame.turn().resume(action, messageId);
    }

    /**
     * 按节点 ID 从图上查找节点。
     *
     * @param nodeId 节点 id
     * @param graph  当前任务图
     * @return 匹配节点；图上没有则为 {@code null}
     */
    private static @Nullable TaskNode nodeOf(String nodeId, TaskGraph graph) {
        return graph.getNodes().stream()
            .filter(node -> nodeId.equals(node.getNodeId()))
            .findFirst().orElse(null);
    }

    /**
     * 把停图回执挂到帧上，结束图主循环。
     *
     * @param frame 当前图执行帧
     * @param halt  停图副作用，携带本轮结果
     */
    private void halt(GraphFrame frame, GraphEffect.Halt halt) {
        frame.halt(halt.result());
    }
}
