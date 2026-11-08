package com.g2rain.agent.runtime.engine.flow.graph;


import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.ArgSpec;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.hydrate.ValueRef;
import com.g2rain.agent.runtime.domain.state.SourceType;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.engine.state.DefaultTurnScope;
import com.g2rain.agent.runtime.support.ArgsBrief;
import com.g2rain.agent.runtime.support.RuntimeUtils;
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
import java.util.LinkedHashMap;
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

        log.debug("[EffectHandler] apply taskId={} effects={}",
            frame.taskId(), effects
        );

        effects.forEach(effect -> {
            switch (effect) {
                case GraphEffect.SaveSuspend s -> saveSuspend(frame, s);
                case GraphEffect.ClearSuspend c -> clearSuspend(frame, c);
                case GraphEffect.Halt h -> halt(frame, h);
            }
        });
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
     * 待批参数的可读明细：用户据此授权，内容须完整。
     *
     * <p>按契约渲染，不区分技能：参数脱敏后不截断；平台注入的参数不展示；只能取自查询结果的引用参数
     * 换成对象名称展示，找不到名称时不展示。</p>
     *
     * @param frame   当前图执行帧，用于取技能契约与会话实体
     * @param nodeId  挂起节点 id
     * @param pending 待批准的写动作；为空时不生成明细
     * @return 如「文章标题：G2rain 2.0，内容空间：官网」；没有可展示参数时为 {@code null}
     */
    private @Nullable String approvalDetail(GraphFrame frame, String nodeId, @Nullable PendingAction pending) {
        if (Objects.isNull(pending)) {
            return null;
        }

        ToolSpec spec = toolSpec(frame, nodeId, pending.toolName());
        JsonNode masked = JSON.str2node(ArgsBrief.of(pending.args(), spec, Integer.MAX_VALUE));
        if (Objects.isNull(masked) || !masked.isObject()) {
            return null;
        }

        Map<String, String> names = entityNames(frame);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : masked.properties()) {
            ArgSpec arg = argOf(spec, field.getKey());
            if (isPlatform(arg)) {
                continue;
            }

            JsonNode value = field.getValue();
            String text = isReference(arg) ? referenceText(names, value)
                : value.isValueNode() ? value.asString() : JSON.obj2str(value);
            if (Objects.isNull(text)) {
                continue;
            }

            String label = Objects.nonNull(arg) && Strings.isNotBlank(arg.getLabel()) ? arg.getLabel() : field.getKey();
            parts.add(label + "：" + text);
        }

        return parts.isEmpty() ? null : String.join("，", parts);
    }

    /**
     * 按字段名取参数契约；数组参数在契约中以 {@code 字段名[]} 声明。
     *
     * @param spec 工具规格；可空
     * @param name 参数名
     * @return 参数契约；找不到为 {@code null}
     */
    private static @Nullable ArgSpec argOf(@Nullable ToolSpec spec, String name) {
        if (Objects.isNull(spec)) {
            return null;
        }

        ArgSpec arg = spec.getArgs().get(name);
        return Objects.nonNull(arg) ? arg : spec.getArgs().get(name + RuntimeUtils.WILDCARD_ARRAY_INDEX);
    }

    /**
     * 引用参数的展示文本：标识换成对象名称，数组逐个换后以「、」连接。
     *
     * @param names 对象名称表，键形如 {@code type:id}
     * @param value 参数值
     * @return 名称文本；一个名称都找不到为 {@code null}
     */
    private static @Nullable String referenceText(Map<String, String> names, JsonNode value) {
        if (!value.isArray()) {
            return entityName(names, value.asString());
        }

        List<String> found = new ArrayList<>();
        value.forEach(item -> {
            String name = entityName(names, item.asString());
            if (Objects.nonNull(name)) {
                found.add(name);
            }
        });
        return found.isEmpty() ? null : String.join("、", found);
    }

    /**
     * 参数是否只能取自查询结果或前情（引用已有对象的标识），不由用户或模型直接给出。
     *
     * @param arg 参数契约；可空
     * @return 引用参数时为 {@code true}
     */
    private static boolean isReference(@Nullable ArgSpec arg) {
        return Objects.nonNull(arg) && !arg.allows(SourceType.USER) && !arg.allows(SourceType.MODEL)
            && (arg.allows(SourceType.EVIDENCE) || arg.allows(SourceType.PRIOR_TASK));
    }

    /**
     * 可用于展示的对象名称，键形如 {@code type:id}：会话实体在轮末才并入，本轮成功证据带出的实体一并纳入。
     *
     * @param frame 当前图执行帧
     * @return 对象名称表
     */
    private static Map<String, String> entityNames(GraphFrame frame) {
        Map<String, String> names = new LinkedHashMap<>();
        // 只有 id 没有名称的实体（展示名回落为 id）不占位，留给本轮证据补上名称
        frame.turn().entities().forEach((key, entry) -> {
            if (!key.endsWith(":" + entry.displayName())) {
                names.put(key, entry.displayName());
            }
        });
        if (!(frame.turn() instanceof DefaultTurnScope turn)) {
            return names;
        }

        frame.graph().getNodes().forEach(node -> turn.node(node.getNodeId()).ifPresent(scope ->
            scope.evidence().stream()
                .filter(head -> head.status() == EvidenceStatus.SUCCESS)
                .map(EvidenceHead::entities)
                .filter(Objects::nonNull)
                .map(JSON::str2node)
                .filter(Objects::nonNull)
                .forEach(entities -> entities.forEach(entity -> {
                    String name = JSON.asString(entity.get("displayName"));
                    if (Strings.isNotBlank(name)) {
                        names.putIfAbsent(JSON.asString(entity.get("type")) + ":" + JSON.asString(entity.get("id")), name);
                    }
                }))
        ));
        return names;
    }

    /**
     * 按标识找对象名称。
     *
     * @param names 对象名称表，键形如 {@code type:id}
     * @param id    参数值
     * @return 对象名称；找不到为 {@code null}
     */
    private static @Nullable String entityName(Map<String, String> names, String id) {
        String suffix = ":" + id;
        return names.entrySet().stream()
            .filter(entry -> entry.getKey().endsWith(suffix))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }

    /**
     * 参数是否取自平台字段（{@code from: platform.*}）；平台字段不进审批摘要。
     *
     * @param arg 参数契约；可空
     * @return 平台字段时为 {@code true}
     */
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
