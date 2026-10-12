package com.g2rain.agent.runtime.engine.tools.policy;


import com.g2rain.agent.runtime.domain.graph.EdgeType;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 根据产出、完成目标和证据状态判断节点是否可以结束。
 *
 * <p>顺序：产出非空 → 未收口证据 → 必填 outputs 与来源 → 节点完成目标（target 工具须已完成）
 * → 无目标且无产出声明时至少完成过一次业务调用。target 可读本节点与委派前置节点的证据。</p>
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

        FinishDecision outputs = requiredOutputs(context);
        if (!outputs.allowed()) {
            return outputs;
        }

        // 有完成目标：目标写工具须已成功
        String target = context.target();
        if (Objects.nonNull(target)) {
            return targetDone(context, target)
                ? FinishDecision.allow()
                : FinishDecision.reject(ModelHint.FINISH_NEED_TARGET.text("tool", target));
        }

        // 无完成目标、契约也没声明产出：至少完成过一次业务调用（空结果也算）
        if (lacksDeclaredOutputs(context.contract()) && context.expectsBusinessTools() && !anyBusinessDone(context)) {
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
        // 只查必填产出：依次要求有值、来源类型合规、常量一致、来源可溯
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
     * 目标写工具是否已在本节点或其委派前置节点完成。
     *
     * @param context 结束判定现场
     * @param target  目标工具注册名
     * @return 已有完成证据
     */
    private boolean targetDone(FinishContext context, String target) {
        EvidenceHead local = latest(context.node(), target);
        if (Objects.nonNull(local) && local.done()) {
            return true;
        }

        ToolExecutionContext execution = context.execution();
        String nodeId = context.nodeId();
        if (Objects.isNull(execution) || Strings.isBlank(nodeId)) {
            return false;
        }

        return execution.getGraph().getEdges().stream()
            .filter(edge -> edge.getType() == EdgeType.DELEGATE && Objects.equals(nodeId, edge.getTo()))
            .map(edge -> stateFacade.evidenceOf(execution.getFrame().turn(), edge.getFrom()))
            .flatMap(List::stream)
            .filter(head -> Objects.equals(target, head.toolName()))
            .anyMatch(EvidenceHead::done);
    }

    /**
     * 本节点是否已完成过任意业务调用。
     *
     * @param context 结束判定现场
     * @return 存在完成证据
     */
    private static boolean anyBusinessDone(FinishContext context) {
        NodeScope node = context.node();
        return Objects.nonNull(node) && node.evidence().stream().anyMatch(EvidenceHead::done);
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
}
