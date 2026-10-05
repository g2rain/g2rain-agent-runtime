package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolCall;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.engine.state.PrewriteResult;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.ToolInterceptor;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

/**
 * 写工具调用前门：确认闸与预写登记。
 *
 * <p>只处理契约副作用为 WRITE 的业务工具。未确认则短路；已有 SUCCESS 证据则复用观测，
 * 未收口证据则阻断，均不会再次发起外部写副作用。</p>
 *
 * @author alpha
 * @since 2026/10/4
 */
@Order(5)
@Component
public class WriteGateInterceptor implements ToolInterceptor {

    private static final JsonCodec JSON = JsonCodecFactory.instance();

    private final RuntimeStateFacade stateFacade;

    private final RuntimeProperties runtimeProperties;

    /**
     * 创建写工具前门。
     *
     * @param stateFacade       任务记忆读写门面，用于预写登记
     * @param runtimeProperties 运行时配置，含写确认旁路工具名单
     */
    public WriteGateInterceptor(RuntimeStateFacade stateFacade, RuntimeProperties runtimeProperties) {
        this.stateFacade = stateFacade;
        this.runtimeProperties = runtimeProperties;
    }

    /**
     * 确认通过后预写；Proceed 把 evidenceId 写入请求供 after 结算。
     *
     * @param request 当前调用
     * @return 复用或阻断观测；允许继续时为空
     */
    @Override
    public Optional<Observation> before(ToolInvokeRequest request) {
        if (ToolCallbacks.isBuiltin(request.toolCallback())) {
            return Optional.empty();
        }

        ToolSpec contract = request.contract();
        if (Objects.isNull(contract) || contract.sideEffectOrDefault() != SideEffect.WRITE) {
            return Optional.empty();
        }

        boolean bypass = runtimeProperties.getState().getWriteConfirmBypassTools().contains(request.toolName());
        if (contract.requiresConfirm() || !bypass) {
            if (!request.context().approvedWrite()) {
                // 记下被拦下的调用：审批只认这一次，待批参数取已过绑定与契约校验的值
                String args = request.arguments();
                request.context().scope().note(NodeNoteKey.GATED_WRITE,
                    new PendingAction(request.toolName(), args, RuntimeUtils.argsHash(argsNode(args))));
                return Optional.of(Observation.failure(
                    ModelHint.WRITE_NEED_APPROVAL.text(), RuntimeErrorCode.APPROVAL_REQUIRED.code()
                ));
            }
        }

        JsonNode args = argsNode(request.arguments());
        String idempotencyKey = IdempotencyKeyBuilder.effectiveKey(request.toolName(), contract, args);
        ToolCall call = new ToolCall(request.toolName(), args, idempotencyKey, SideEffect.WRITE, contract);
        PrewriteResult prewrite = stateFacade.prewrite(request.context().scope(), call);
        // Proceed 才放行；Reused/Blocked 都带观测返回，工具不会被调用
        return switch (prewrite) {
            case PrewriteResult.Proceed proceed -> {
                request.evidenceId(proceed.evidenceId());
                yield Optional.empty();
            }
            case PrewriteResult.Reused reused -> Optional.of(reusedObservation(request, reused.evidenceId()));
            case PrewriteResult.Blocked blocked -> Optional.of(blockedObservation(blocked.status()));
        };
    }

    /**
     * 写闸阻断时的失败观测。
     *
     * @param status 预写阻断状态
     * @return 待核验或进行中失败观测
     */
    private static Observation blockedObservation(EvidenceStatus status) {
        if (status == EvidenceStatus.UNKNOWN || status == EvidenceStatus.UNKNOWN_PRE_CALL) {
            return Observation.failure(
                RuntimeErrorCode.WRITE_PENDING_VERIFY.getMessage(),
                RuntimeErrorCode.WRITE_PENDING_VERIFY.code()
            );
        }

        return Observation.failure(
            RuntimeErrorCode.WRITE_IN_FLIGHT.getMessage(),
            RuntimeErrorCode.WRITE_IN_FLIGHT.code()
        );
    }

    /**
     * 复用已有写证据时的成功观测。
     *
     * @param request    当前工具请求
     * @param evidenceId 复用的证据 id
     * @return 带原报文体的成功观测
     */
    private Observation reusedObservation(ToolInvokeRequest request, String evidenceId) {
        JsonNode payload = stateFacade.payload(
            request.context().getFrame().turn(), request.context().getTaskId(), evidenceId
        );
        String brief = request.context().scope().evidence().stream()
            .filter(head -> Objects.equals(head.evidenceId(), evidenceId))
            .map(EvidenceHead::resultBrief)
            .filter(Strings::isNotBlank)
            .findFirst()
            .orElse(null);
        if (Strings.isBlank(brief)) {
            brief = JSON.obj2str(payload);
        }
        if (Strings.isBlank(brief)) {
            brief = ModelHint.WRITE_REUSED.text();
        }
        return Observation.success(brief, payload);
    }

    /**
     * 入参文本转 JSON 对象。
     *
     * @param arguments 原始入参；空白按 {@code {}}
     * @return 非空 JSON 节点
     */
    private static JsonNode argsNode(String arguments) {
        JsonNode node = JSON.str2node(Strings.defaultIfBlank(arguments, "{}"));
        return Objects.isNull(node) ? JSON.str2node("{}") : node;
    }
}
