package com.g2rain.agent.runtime.engine.flow.graph;

import com.g2rain.agent.runtime.domain.execute.ExecutorResult;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.hydrate.VerifySpec;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.Outcome;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.engine.state.DefaultNodeScope;
import com.g2rain.agent.runtime.engine.state.EvidenceFlow;
import com.g2rain.agent.runtime.engine.tools.ToolInvoker;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 未知写收口：节点开跑前对仍未知的写证据调契约 verify 工具回查；调不了则 VERIFY 挂起。
 *
 * @author alpha
 * @since 2026/10/12
 */
@Component
@RequiredArgsConstructor
public class WriteVerifier {

    /**
     * 解析 verify 入参时使用的 JSON 编解码器。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 工具调用入口：调 verify 工具。
     */
    private final ToolInvoker toolInvoker;

    /**
     * 证据流：读原始请求、改证据态。
     */
    private final EvidenceFlow evidenceFlow;

    /**
     * 仍未知的写证据：先调 verify.tool；调不了则 VERIFY 挂起。
     *
     * @param node      当前节点
     * @param frame     当前图执行帧
     * @param skill     当前技能；可空
     * @param tools     本节点可见工具
     * @param nodeScope 当前节点作用域
     * @param effects   收集挂起副作用
     * @return 已发出 VERIFY 挂起时为 {@code true}
     */
    public boolean haltUnknown(TaskNode node, GraphFrame frame, @Nullable Skill skill,
                               List<ToolCallback> tools, DefaultNodeScope nodeScope,
                               List<GraphEffect> effects) {
        for (EvidenceHead head : List.copyOf(nodeScope.evidence())) {
            if (!isUnknownWrite(head)) {
                continue;
            }
            if (settleByVerifyTool(node, frame, skill, tools, nodeScope, head)) {
                continue;
            }
            String ask = RuntimeMessage.VERIFY_ASK.text();
            node.setStatus(NodeStatus.SUSPENDED);
            frame.turn().mark(node.getNodeId(), NodeStatus.SUSPENDED, null, null);
            effects.add(new GraphEffect.SaveSuspend(node.getNodeId(), CheckpointType.VERIFY, ask, null, List.of(),
                List.of()));
            effects.add(new GraphEffect.Halt(new ExecutorResult(TaskStatus.SUSPENDED, node.getNodeId(), ask,
                CheckpointType.VERIFY)));
            return true;
        }
        return false;
    }

    /**
     * 调用契约 verify 工具收口未知写证据。
     *
     * @param node      当前节点
     * @param frame     当前图执行帧
     * @param skill     当前技能；可空
     * @param tools     本节点可见工具
     * @param nodeScope 当前节点作用域
     * @param head      待核实的写证据
     * @return 已调用 verify 工具则为 {@code true}；无工具可调则为 {@code false}
     */
    private boolean settleByVerifyTool(TaskNode node, GraphFrame frame, @Nullable Skill skill,
                                       List<ToolCallback> tools, DefaultNodeScope nodeScope, EvidenceHead head) {
        if (Objects.isNull(skill) || Objects.isNull(skill.contract())) {
            return false;
        }
        ToolSpec spec = skill.contract().tool(head.toolName());
        if (Objects.isNull(spec) || Objects.isNull(spec.getVerify()) || Strings.isBlank(spec.getVerify().getTool())) {
            return false;
        }
        VerifySpec verify = spec.getVerify();
        ToolCallback callback = ToolCallbacks.find(tools, verify.getTool());
        if (Objects.isNull(callback)) {
            return false;
        }
        ToolExecutionContext toolContext = new ToolExecutionContext(frame, node, nodeScope);
        Observation observation = toolInvoker.invoke(new ToolInvokeRequest(
            toolContext, callback, verifyArgs(verify, head)
        ));
        if (observation.outcome() == Outcome.SUCCESS) {
            evidenceFlow.applyOutcome(nodeScope, head, EvidenceStatus.SUCCESS, true, FailureKind.NONE);
        } else {
            evidenceFlow.applyOutcome(nodeScope, head, EvidenceStatus.FAILED, false, FailureKind.NONE);
        }
        return true;
    }

    /**
     * 按 verify.args 从原始请求 JSON 抽出入参；无映射时整份请求透传。
     *
     * @param verify 工具上的核实声明
     * @param head   待核实的写证据
     * @return 调用 verify 工具的 JSON 参数
     */
    private String verifyArgs(VerifySpec verify, EvidenceHead head) {
        String request = evidenceFlow.requestJson(head.evidenceId());
        if (Collections.isEmpty(verify.getArgs())) {
            return Strings.isBlank(request) ? "{}" : request;
        }
        JsonNode original = Strings.isBlank(request) ? JSON.str2node("{}") : JSON.str2node(request);
        Map<String, Object> built = new LinkedHashMap<>();
        verify.getArgs().forEach((key, spec) -> {
            if (Objects.nonNull(spec.getConstant())) {
                built.put(key, spec.getConstant());
            } else if (Strings.isNotBlank(spec.getFrom()) && spec.getFrom().startsWith("call.")) {
                JsonNode value = JSON.lookupNode(original, spec.getFrom().substring("call.".length()));
                if (Objects.nonNull(value)) {
                    built.put(key, value);
                }
            }
        });
        return JSON.obj2str(built);
    }

    /**
     * 是否为尚未收口的写证据（UNKNOWN 或 UNKNOWN_PRE_CALL）。
     *
     * @param head 证据头
     * @return 仍是未知写证据时为 {@code true}
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    static boolean isUnknownWrite(EvidenceHead head) {
        if (head.sideEffect() != SideEffect.WRITE) {
            return false;
        }
        return head.status() == EvidenceStatus.UNKNOWN || head.status() == EvidenceStatus.UNKNOWN_PRE_CALL;
    }
}
