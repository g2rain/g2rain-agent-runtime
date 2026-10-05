package com.g2rain.agent.runtime.engine.tools.policy;


import com.g2rain.agent.runtime.domain.graph.EdgeType;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.hydrate.DoneWhenExpr;
import com.g2rain.agent.runtime.domain.hydrate.OutputSpec;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.SourceType;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.guard.SourceVerifier;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 根据产出、完成条件和证据状态判断节点是否可以结束。
 *
 * <p>顺序：产出非空 → 未收口证据 / 补参 → 必填 outputs 与来源 → doneWhen 或默认算法
 * （target / 必填 outputs）→ 无契约时的业务工具兜底。target 可读本节点与委派前置节点的成功证据。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Component
@RequiredArgsConstructor
public class DefaultFinishPolicy implements FinishPolicy {

    /**
     * JSON 编解码：读工作区节点值。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 检查输出来源是否满足契约声明的引用规则。
     */
    private final SourceVerifier sourceVerifier;

    /**
     * 读委派前置节点证据 / outputs。
     */
    private final RuntimeStateFacade stateFacade;

    /**
     * 评估是否允许 finish。
     *
     * @param context 评估入参
     * @return 允许或拒绝及原因
     */
    @Override
    public FinishDecision evaluate(FinishContext context) {
        Object o = context.output();
        // 产出为空直接拒
        if (Objects.isNull(o) || (o instanceof String t && Strings.isBlank(t))) {
            return FinishDecision.reject(ModelHint.FINISH_MISSING_OUTPUT.text());
        }

        if (hasOpenEvidence(context.node())) {
            return FinishDecision.reject(ModelHint.FINISH_OPEN_EVIDENCE.text());
        }

        NodeScope current = context.node();
        if (Objects.nonNull(current) && Objects.nonNull(current.repair())) {
            return FinishDecision.reject(ModelHint.FINISH_REPAIR_PENDING.text());
        }

        FinishDecision outputs = requiredOutputs(context);
        if (!outputs.allowed()) {
            return outputs;
        }

        DoneWhenExpr doneWhen = context.doneWhenExpr();
        if (Objects.nonNull(doneWhen)) {
            return evaluateExpression(context, doneWhen, context.targetToolName());
        }

        String targetTool = context.targetToolName();
        if (Objects.nonNull(targetTool) && !toolSucceeded(context, targetTool)) {
            return FinishDecision.reject(ModelHint.FINISH_NEED_TARGET.text("tool", targetTool));
        }

        if (Objects.isNull(targetTool) && lacksDeclaredOutputs(context.contract())
            && Objects.nonNull(context.node())
            && Strings.isNotBlank(context.nodeId())
            && context.expectsBusinessTools()
            && !anyBusinessSucceeded(context)) {
            return FinishDecision.reject(ModelHint.FINISH_NEED_BUSINESS.text());
        }

        return FinishDecision.allow();
    }

    /**
     * 契约必填 outputs 齐全且来源校验通过。
     *
     * @param context 结束判定现场
     * @return 允许结束或拒绝原因
     */
    private FinishDecision requiredOutputs(FinishContext context) {
        SkillContract contract = context.contract();
        if (Objects.isNull(contract)) {
            return FinishDecision.allow();
        }
        Map<String, OutputSpec> outputs = contract.getOutputs();
        if (Collections.isEmpty(outputs)) {
            return FinishDecision.allow();
        }

        NodeScope node = context.node();
        if (Objects.isNull(node)) {
            return FinishDecision.reject(ModelHint.FINISH_NO_NODE.text());
        }

        ToolExecutionContext execution = context.execution();
        for (Map.Entry<String, OutputSpec> entry : outputs.entrySet()) {
            OutputSpec spec = entry.getValue();
            if (!spec.isRequired()) {
                continue;
            }

            String key = entry.getKey();
            Sourced<?> sourced = node.outputs().get(key);
            if (Objects.isNull(sourced) || Strings.isBlank(String.valueOf(sourced.value()))) {
                return FinishDecision.reject(ModelHint.FINISH_OUTPUT_REQUIRED.text("key", key));
            }

            if (!spec.allows(sourced.source().type())) {
                return FinishDecision.reject(ModelHint.FINISH_OUTPUT_SOURCE_TYPE.text("key", key));
            }
            if (sourced.source().type() == SourceType.CONTRACT) {
                if (Objects.isNull(spec.getConstant())
                    || !JSON.nodesEqual(spec.getConstant(), JSON.obj2node(sourced.value()))) {
                    return FinishDecision.reject(ModelHint.FINISH_OUTPUT_CONST.text("key", key));
                }
            }
            if (Objects.nonNull(execution) && !sourceVerifier.verify(sourced, execution)) {
                return FinishDecision.reject(ModelHint.FINISH_OUTPUT_SOURCE.text("key", key));
            }
        }

        return FinishDecision.allow();
    }

    /**
     * 契约是否没有声明任何 output。
     *
     * @param contract 技能契约；可空
     * @return 空契约或空 outputs 为 {@code true}
     */
    private static boolean lacksDeclaredOutputs(@Nullable SkillContract contract) {
        return Objects.isNull(contract) || Collections.isEmpty(contract.getOutputs());
    }

    /**
     * 按装载期 AST 评估；OR 任一通过、AND 全过。
     *
     * @param context    结束判定现场
     * @param expr       doneWhen 表达式
     * @param targetTool 目标工具名；可空
     * @return 允许结束或拒绝原因
     */
    private FinishDecision evaluateExpression(FinishContext context, DoneWhenExpr expr, @Nullable String targetTool) {
        return switch (expr) {
            case DoneWhenExpr.Or(var children) -> {
                List<String> fails = new ArrayList<>();
                for (DoneWhenExpr child : children) {
                    FinishDecision decision = evaluateExpression(context, child, targetTool);
                    if (decision.allowed()) {
                        yield FinishDecision.allow();
                    }
                    String reason = decision.reason();
                    if (Strings.isNotBlank(reason)) {
                        fails.add(reason);
                    }
                }
                yield FinishDecision.reject(ModelHint.FINISH_OR_FAILED.text("reasons", String.join("; ", fails)));
            }
            case DoneWhenExpr.And(var children) -> {
                for (DoneWhenExpr child : children) {
                    FinishDecision decision = evaluateExpression(context, child, targetTool);
                    if (!decision.allowed()) {
                        yield decision;
                    }
                }
                yield FinishDecision.allow();
            }
            case DoneWhenExpr.Atom atom -> evaluateAtomic(context, atom, targetTool);
        };
    }

    /**
     * 评单条原子 doneWhen
     *
     * @param context    结束判定现场
     * @param clause     原子条件
     * @param targetTool 目标工具名；可空
     * @return 允许结束或拒绝原因
     */
    private FinishDecision evaluateAtomic(FinishContext context, DoneWhenExpr.Atom clause, @Nullable String targetTool) {
        return switch (clause.kind()) {
            case TARGET_TOOL_SUCCEEDED -> {
                if (Objects.isNull(targetTool)) {
                    yield FinishDecision.reject(ModelHint.FINISH_DONE_WHEN_NEED_TARGET.text());
                }

                yield toolSucceeded(context, targetTool)
                    ? FinishDecision.allow()
                    : FinishDecision.reject(ModelHint.FINISH_NEED_TARGET.text("tool", targetTool));
            }
            case TOOL_SUCCEEDED, EVIDENCE_TOOL_SUCCEEDED -> {
                String tool = clause.entity();
                yield toolSucceeded(context, tool)
                    ? FinishDecision.allow()
                    : FinishDecision.reject(ModelHint.FINISH_EVIDENCE_TOOL.text("tool", tool));
            }
            case TOOL_EMPTY -> {
                String tool = clause.entity();
                yield toolEmpty(context, tool)
                    ? FinishDecision.allow()
                    : FinishDecision.reject(ModelHint.FINISH_TOOL_NOT_EMPTY.text("tool", tool));
            }
            case EVIDENCE_KEY_EXISTS -> evidenceKeyExists(context, clause.field());
            case OUTPUT_FIELD_EXISTS -> outputExists(context, clause.field());
            case OUTPUT_LENGTH_GTE -> outputLengthGte(context, clause.field(), clause.minLength());
            case OUTPUT_ITEM_FIELD_EXISTS -> outputItemFieldExists(context, clause.entity(), clause.field());
            case UNKNOWN -> FinishDecision.reject(ModelHint.FINISH_DONE_WHEN_UNKNOWN.text("raw", clause.raw()));
        };
    }

    /**
     * 本节点 output 键是否已写入
     *
     * @param context 结束判定现场
     * @param field   output 键；可空
     * @return 允许结束或拒绝原因
     */
    private FinishDecision outputExists(FinishContext context, @Nullable String field) {
        if (Strings.isBlank(field)) {
            return FinishDecision.reject(ModelHint.FINISH_OUTPUT_FIELD_BLANK.text());
        }

        Object value = outputValue(context, field);
        if (Objects.nonNull(value) && !Strings.isBlank(String.valueOf(value))) {
            return FinishDecision.allow();
        }

        return FinishDecision.reject(ModelHint.FINISH_OUTPUT_MISSING.text("key", field));
    }

    /**
     * 本节点集合 output 长度是否达标
     *
     * @param context    结束判定现场
     * @param collection 集合 output 键；可空
     * @param minLength  最短长度；可空则按 1
     * @return 允许结束或拒绝原因
     */
    private FinishDecision outputLengthGte(FinishContext context, @Nullable String collection, @Nullable Integer minLength) {
        if (Strings.isBlank(collection)) {
            return FinishDecision.reject(ModelHint.FINISH_OUTPUT_COLLECTION_BLANK.text());
        }

        int required = Objects.nonNull(minLength) ? minLength : 1;
        String reject = rejectIfCollectionLengthLt(outputValue(context, collection), collection, required);
        return Objects.isNull(reject) ? FinishDecision.allow() : FinishDecision.reject(reject);
    }

    /**
     * 本节点集合 output 每项是否都有指定字段
     *
     * @param context    结束判定现场
     * @param collection 集合 output 键；可空
     * @param field      项内字段；可空
     * @return 允许结束或拒绝原因
     */
    private FinishDecision outputItemFieldExists(FinishContext context, @Nullable String collection, @Nullable String field) {
        if (Strings.isBlank(collection) || Strings.isBlank(field)) {
            return FinishDecision.reject(ModelHint.FINISH_OUTPUT_PREDICATE.text());
        }

        String reject = rejectIfAnyItemMissingField(outputValue(context, collection), collection, field);
        return Objects.isNull(reject) ? FinishDecision.allow() : FinishDecision.reject(reject);
    }

    /**
     * evidence 某键是否存在
     *
     * @param context 结束判定现场
     * @param key     工具名或抽取键；可空
     * @return 允许结束或拒绝原因
     */
    private FinishDecision evidenceKeyExists(FinishContext context, @Nullable String key) {
        NodeScope node = context.node();
        if (Strings.isBlank(key) || Objects.isNull(node)) {
            return FinishDecision.reject(ModelHint.FINISH_EVIDENCE_MISSING.text("key", key));
        }

        boolean hit = node.evidence().stream().filter(DefaultFinishPolicy::isToolCallSuccess).anyMatch(o ->
            Objects.equals(o.toolName(), key) || (Objects.nonNull(o.extractCounts()) && o.extractCounts().containsKey(key))
        );

        return hit ? FinishDecision.allow() : FinishDecision.reject(ModelHint.FINISH_EVIDENCE_MISSING.text("key", key));
    }

    /**
     * 读取本节点已写入的 output 值。
     *
     * @param context 结束判定现场
     * @param field   output 键；可空
     * @return 产出值；节点或键缺失为 {@code null}
     */
    private static @Nullable Object outputValue(FinishContext context, @Nullable String field) {
        NodeScope node = context.node();
        if (Objects.isNull(node) || Strings.isBlank(field)) {
            return null;
        }

        var sourced = node.outputs().get(field);
        return Objects.isNull(sourced) ? null : sourced.value();
    }

    /**
     * 指定工具是否在本节点或其委派前置节点已成功；空结果默认不算成功。
     *
     * @param context  结束判定现场
     * @param toolName 工具名；可空
     * @return 已有成功证据
     */
    private boolean toolSucceeded(FinishContext context, @Nullable String toolName) {
        EvidenceHead local = latest(context.node(), toolName);
        if (Objects.nonNull(local) && isToolCallSuccess(local)) {
            return true;
        }

        ToolExecutionContext execution = context.execution();
        TaskGraph graph = Objects.isNull(execution) ? null : execution.getGraph();
        String nodeId = context.nodeId();
        if (Objects.isNull(execution) || Strings.isBlank(nodeId) || Strings.isBlank(toolName)) {
            return false;
        }

        return graph.getEdges().stream()
            .filter(edge -> edge.getType() == EdgeType.DELEGATE && Objects.equals(nodeId, edge.getTo()))
            .map(edge -> stateFacade.evidenceOf(execution.getFrame().turn(), edge.getFrom()))
            .flatMap(List::stream)
            .filter(head -> Objects.equals(toolName, head.toolName()))
            .anyMatch(DefaultFinishPolicy::isToolCallSuccess);
    }

    /**
     * 指定工具最近一次成功结果是否为空。
     *
     * @param context  结束判定现场
     * @param toolName 工具名；可空
     * @return 成功且 empty=true
     */
    private boolean toolEmpty(FinishContext context, @Nullable String toolName) {
        EvidenceHead head = latest(context.node(), toolName);
        return Objects.nonNull(head) && head.status() == EvidenceStatus.SUCCESS && Boolean.TRUE.equals(head.empty());
    }

    /**
     * 本节点是否已有任意业务工具成功证据。
     *
     * @param context 结束判定现场
     * @return 存在成功证据
     */
    private boolean anyBusinessSucceeded(FinishContext context) {
        NodeScope node = context.node();
        if (Objects.isNull(node)) {
            return false;
        }

        return node.evidence().stream().anyMatch(DefaultFinishPolicy::isToolCallSuccess);
    }

    /**
     * 本节点某工具最近一条证据。
     *
     * @param node     节点视图；可空
     * @param toolName 工具名；可空
     * @return 最近证据；节点或工具名为空时为 {@code null}
     */
    private static @Nullable EvidenceHead latest(@Nullable NodeScope node, @Nullable String toolName) {
        if (Objects.isNull(node) || Strings.isBlank(toolName)) {
            return null;
        }

        EvidenceHead last = null;
        for (EvidenceHead head : node.evidence()) {
            if (toolName.equals(head.toolName())) {
                last = head;
            }
        }

        return last;
    }

    /**
     * 节点是否仍有未收口证据。
     *
     * @param node 节点视图；可空
     * @return 存在 STARTED / UNKNOWN 类状态
     */
    private static boolean hasOpenEvidence(@Nullable NodeScope node) {
        if (Objects.isNull(node)) {
            return false;
        }

        return node.evidence().stream().anyMatch(head ->
            head.status() == EvidenceStatus.STARTED
                || head.status() == EvidenceStatus.UNKNOWN
                || head.status() == EvidenceStatus.UNKNOWN_PRE_CALL
        );
    }

    /**
     * 证据是否算工具调用成功（SUCCESS 且非业务失败、非空结果）。
     *
     * @param head 证据头
     * @return 是否算成功
     */
    private static boolean isToolCallSuccess(EvidenceHead head) {
        if (head.status() != EvidenceStatus.SUCCESS) {
            return false;
        }

        if (Objects.nonNull(head.businessOk()) && !head.businessOk()) {
            return false;
        }

        return !Boolean.TRUE.equals(head.empty());
    }

    /**
     * 集合长度不足时给出拒绝文案。
     *
     * @param collectionValue 集合值；可空
     * @param collection      集合键；可空
     * @param required        最短长度
     * @return 拒绝文案；达标为 {@code null}
     */
    @Nullable
    private static String rejectIfCollectionLengthLt(@Nullable Object collectionValue, @Nullable String collection, int required) {
        if (Strings.isBlank(collection)) {
            return ModelHint.FINISH_OUTPUT_COLLECTION_BLANK.text();
        }

        JsonNode arr = JSON.obj2node(collectionValue);
        if (Objects.isNull(arr) || !arr.isArray()) {
            return ModelHint.FINISH_OUTPUT_NOT_COLLECTION.text("key", collection);
        }

        if (arr.size() >= required) {
            return null;
        }

        return ModelHint.FINISH_OUTPUT_LENGTH.text(
            "key", collection, "size", String.valueOf(arr.size()), "required", String.valueOf(required)
        );
    }

    /**
     * 集合任一项缺字段时给出拒绝文案。
     *
     * @param collectionValue 集合值；可空
     * @param collection      集合键；可空
     * @param field           项内字段；可空
     * @return 拒绝文案；全部具备为 {@code null}
     */
    @Nullable
    private static String rejectIfAnyItemMissingField(@Nullable Object collectionValue, @Nullable String collection, @Nullable String field) {
        if (Strings.isBlank(collection) || Strings.isBlank(field)) {
            return ModelHint.FINISH_OUTPUT_PREDICATE.text();
        }

        JsonNode arr = JSON.obj2node(collectionValue);
        if (Objects.isNull(arr) || !arr.isArray() || arr.isEmpty()) {
            return ModelHint.FINISH_OUTPUT_EMPTY.text("key", collection);
        }

        for (int i = 0, j = arr.size(); i < j; i++) {
            JsonNode item = arr.get(i);
            if (!item.isObject()) {
                return ModelHint.FINISH_OUTPUT_ITEM_NOT_OBJECT.text(
                    "key", collection, "index", String.valueOf(i)
                );
            }

            JsonNode value = JSON.lookupNode(item, field);
            if (!JSON.hasValue(value) || Strings.isBlank(JSON.normalizeNode(value))) {
                return ModelHint.FINISH_OUTPUT_ITEM_FIELD.text(
                    "key", collection, "index", String.valueOf(i), "field", field
                );
            }
        }

        return null;
    }
}
