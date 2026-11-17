package com.g2rain.agent.runtime.engine.flow.graph;


import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.spec.ArgSpec;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.spec.ToolSpec;
import com.g2rain.agent.runtime.spec.ValueRef;
import com.g2rain.agent.runtime.spec.SourceType;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.engine.state.DefaultTurnScope;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
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
import tools.jackson.databind.node.ObjectNode;

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
     * 实体键中类型与标识的分隔符：{@code type:id}。
     */
    private static final String KEY_SEPARATOR = ":";

    /**
     * 审批明细中参数名与值的分隔符。
     */
    private static final String LABEL_SEPARATOR = "：";

    /**
     * 审批明细中参数之间的分隔符。
     */
    private static final String FIELD_SEPARATOR = "，";

    /**
     * 审批明细中明细行序号的后缀：{@code 1. }。
     */
    private static final String ROW_INDEX_SUFFIX = ". ";

    /**
     * 契约参数路径中数组项字段的分隔符：{@code 字段名[].子字段}。
     */
    private static final String PATH_SEPARATOR = ".";

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
     * <p>节点找不到则什么也不写。审批挂起时由系统给出要执行的操作与参数，
     * 让用户确认的正是即将执行的那个工具和那组参数；结果帧由收尾统一下发。</p>
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
        PendingAction pending = checkpoint.getPendingAction();
        if (checkpoint.getType() == CheckpointType.APPROVAL && Objects.nonNull(pending)) {
            checkpoint.setQuestion(approvalQuestion(frame, save.nodeId(), checkpoint.getQuestion(), pending));
        }
        frame.turn().suspend(checkpoint);
        frame.turn().focus(save.nodeId());
    }

    /**
     * 审批问题：首行由系统给出工具展示名，模型摘要只作补充，末行是参数明细。
     *
     * <p>操作名取自契约而非模型摘要，摘要与实际调用不一致时用户也能看出要执行的是什么。</p>
     *
     * @param frame   当前图执行帧
     * @param nodeId  挂起节点 id
     * @param summary 模型给出的摘要；可空
     * @param pending 待批准的写动作
     * @return 如「即将执行：发布文章\n发布新版官网文章\n文章标题：G2rain 2.0」
     */
    private String approvalQuestion(GraphFrame frame, String nodeId, @Nullable String summary, PendingAction pending) {
        ToolSpec spec = toolSpec(frame, nodeId, pending.toolName());
        String label = Objects.nonNull(spec) && Strings.isNotBlank(spec.getLabel()) ? spec.getLabel() : pending.toolName();
        List<String> lines = new ArrayList<>();
        lines.add(RuntimeMessage.APPROVAL_ACTION.text("tool", label));
        if (Strings.isNotBlank(summary)) {
            lines.add(summary.trim());
        }

        String detail = approvalDetail(frame, spec, pending);
        if (Strings.isNotBlank(detail)) {
            lines.add(detail);
        }

        return String.join("\n", lines);
    }

    /**
     * 待批参数的可读明细：用户据此授权，内容须完整。
     *
     * <p>按契约渲染，不区分技能：参数脱敏后不截断；平台注入的参数不展示；只能取自查询结果的引用参数
     * 换成对象名称展示，找不到名称时展示原值。对象数组（如订单明细）在普通参数之后逐行展示，
     * 每行字段名取契约 {@code 字段名[].子字段} 的 label。</p>
     *
     * @param frame   当前图执行帧，用于取会话实体
     * @param spec    待批工具规格；可空
     * @param pending 待批准的写动作
     * @return 如「标题：示例，明细：\n1. 名称：A，数量：2」；没有可展示参数时为 {@code null}
     */
    private @Nullable String approvalDetail(GraphFrame frame, @Nullable ToolSpec spec, PendingAction pending) {
        ObjectNode masked = ArgsBrief.masked(pending.args(), spec);
        Map<String, String> names = entityNames(frame);
        List<String> parts = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : masked.properties()) {
            ArgSpec arg = argOf(spec, field.getKey());
            if (isPlatform(arg)) {
                continue;
            }

            JsonNode value = field.getValue();
            String label = labelOf(arg, field.getKey());
            if (isObjectArray(value)) {
                rows.add(label + LABEL_SEPARATOR);
                rows.addAll(rowLines(names, spec, field.getKey(), value));
                continue;
            }

            parts.add(label + LABEL_SEPARATOR + valueText(names, arg, value));
        }

        List<String> lines = new ArrayList<>();
        if (!parts.isEmpty()) {
            lines.add(String.join(FIELD_SEPARATOR, parts));
        }

        lines.addAll(rows);
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    /**
     * 对象数组逐行渲染：每行「序号. 字段：值，字段：值」，平台注入的字段不展示。
     *
     * @param names 对象名称表，键形如 {@code type:id}
     * @param spec  工具规格；可空
     * @param param 数组参数名
     * @param items 数组值，元素均为对象
     * @return 各行文本
     */
    private static List<String> rowLines(Map<String, String> names, @Nullable ToolSpec spec, String param, JsonNode items) {
        List<String> lines = new ArrayList<>();
        String prefix = param + RuntimeUtils.WILDCARD_ARRAY_INDEX + PATH_SEPARATOR;
        for (int i = 0, j = items.size(); i < j; i++) {
            List<String> cells = new ArrayList<>();
            for (Map.Entry<String, JsonNode> cell : items.get(i).properties()) {
                ArgSpec arg = Objects.isNull(spec) ? null : spec.getArgs().get(prefix + cell.getKey());
                if (isPlatform(arg)) {
                    continue;
                }

                cells.add(labelOf(arg, cell.getKey()) + LABEL_SEPARATOR + valueText(names, arg, cell.getValue()));
            }

            lines.add((i + 1) + ROW_INDEX_SUFFIX + String.join(FIELD_SEPARATOR, cells));
        }

        return lines;
    }

    /**
     * 参数值的展示文本：引用参数换成对象名称，标量取原文，其余序列化为 JSON。
     *
     * @param names 对象名称表
     * @param arg   参数契约；可空
     * @param value 参数值
     * @return 展示文本
     */
    private static String valueText(Map<String, String> names, @Nullable ArgSpec arg, JsonNode value) {
        if (isReference(arg)) {
            return referenceText(names, entityType(arg), value);
        }

        return value.isValueNode() ? value.asString() : JSON.obj2str(value);
    }

    /**
     * 参数展示名：契约 label，未配置时用参数名。
     *
     * @param arg  参数契约；可空
     * @param name 参数名
     * @return 展示名
     */
    private static String labelOf(@Nullable ArgSpec arg, String name) {
        return Objects.nonNull(arg) && Strings.isNotBlank(arg.getLabel()) ? arg.getLabel() : name;
    }

    /**
     * 是否为非空的对象数组（明细行）。
     *
     * @param value 参数值
     * @return 每个元素都是对象时为 {@code true}
     */
    private static boolean isObjectArray(JsonNode value) {
        if (!value.isArray() || value.isEmpty()) {
            return false;
        }

        for (JsonNode item : value) {
            if (!item.isObject()) {
                return false;
            }
        }

        return true;
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
     * 引用参数的展示文本：标识换成对象名称，找不到名称时保留原值；数组逐个换后以「、」连接。
     *
     * @param names 对象名称表，键形如 {@code type:id}
     * @param type  参数指向的实体类型；未声明为 {@code null}
     * @param value 参数值
     * @return 展示文本
     */
    private static String referenceText(Map<String, String> names, @Nullable String type, JsonNode value) {
        if (!value.isArray()) {
            return nameOrId(names, type, value.asString());
        }

        List<String> parts = new ArrayList<>();
        value.forEach(item -> parts.add(nameOrId(names, type, item.asString())));
        return String.join("、", parts);
    }

    /**
     * 参数指向的实体类型：优先取参数契约的 {@code entity}，其次从 {@code from: prior.<type>.<attr>}
     * 或 {@code prior.<type:id>.<attr>} 中解析。
     *
     * @param arg 参数契约；可空
     * @return 实体类型；未声明为 {@code null}
     */
    private static @Nullable String entityType(@Nullable ArgSpec arg) {
        if (Objects.isNull(arg)) {
            return null;
        }

        String declared = arg.getEntity();
        if (Strings.isNotBlank(declared)) {
            return declared.trim();
        }

        ValueRef ref = arg.getValueRef();
        if (Objects.isNull(ref) && ValueRef.isKnown(arg.getFrom())) {
            ref = ValueRef.parse(Objects.requireNonNull(arg.getFrom()));
        }
        if (Objects.isNull(ref) || ref.bag() != ValueRef.Bag.PRIOR) {
            return null;
        }

        String head = ref.path().split("\\.", 2)[0];
        return head.split(KEY_SEPARATOR, 2)[0];
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
            if (!key.endsWith(KEY_SEPARATOR + entry.displayName())) {
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
                        names.putIfAbsent(JSON.asString(entity.get("type")) + KEY_SEPARATOR + JSON.asString(entity.get("id")), name);
                    }
                }))
        ));
        return names;
    }

    /**
     * 按标识找对象名称，找不到或无法确定时返回标识本身。
     *
     * <p>知道类型时按 {@code type:id} 精确查找；不知道类型时只有唯一一个名称匹配才采用，
     * 不同类型的对象标识相同时宁可显示标识，也不显示可能张冠李戴的名称。</p>
     *
     * @param names 对象名称表，键形如 {@code type:id}
     * @param type  实体类型；未知为 {@code null}
     * @param id    参数值
     * @return 对象名称或标识
     */
    private static String nameOrId(Map<String, String> names, @Nullable String type, String id) {
        if (Strings.isNotBlank(type)) {
            return Objects.requireNonNullElse(names.get(type + KEY_SEPARATOR + id), id);
        }

        String suffix = KEY_SEPARATOR + id;
        List<String> found = names.entrySet().stream()
            .filter(entry -> entry.getKey().endsWith(suffix))
            .map(Map.Entry::getValue)
            .distinct()
            .toList();
        return found.size() == 1 ? found.getFirst() : id;
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
     * @return 工具规格；找不到时为 {@code null}
     */
    private static @Nullable ToolSpec toolSpec(GraphFrame frame, String nodeId, String toolName) {
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
