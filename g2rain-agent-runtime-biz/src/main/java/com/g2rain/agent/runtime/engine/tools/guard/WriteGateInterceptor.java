package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.Outcome;
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
 * <p>只处理契约副作用为 WRITE 的业务工具。先查同一写操作：已有 SUCCESS 证据则复用观测，
 * 未收口证据则阻断，都不再申请确认；没有才按需确认，未确认则短路。均不会再次发起外部写副作用。已批准的写操作执行失败时，下一步改为向用户说明并询问如何处理。</p>
 *
 * @author alpha
 * @since 2026/10/4
 */
@Order(5)
@Component
public class WriteGateInterceptor implements ToolInterceptor {

    /**
     * JSON 编解码。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 运行时状态门面：写前查重、读已有结果。
     */
    private final RuntimeStateFacade stateFacade;

    /**
     * 运行时配置：免确认的写工具名单。
     */
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
     * 先查同一写操作的已有结果，没有再按需申请确认，确认通过后预写；Proceed 把 evidenceId 写入请求供 after 结算。
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

        JsonNode args = RuntimeUtils.argsNode(request.arguments());
        String idempotencyKey = IdempotencyKeyBuilder.effectiveKey(request.toolName(), contract, args);
        ToolCall call = new ToolCall(request.toolName(), args, idempotencyKey, SideEffect.WRITE, contract);
        // 同一写操作已成功则直接复用、仍在进行或待核实则等待，都不再申请确认
        Optional<PrewriteResult> existing = stateFacade.existing(request.context().scope(), call);
        if (existing.isPresent()) {
            return observe(request, existing.get());
        }

        boolean bypass = runtimeProperties.getState().getWriteConfirmBypassTools().contains(request.toolName());
        if (contract.requiresConfirm() || !bypass) {
            if (!request.context().approvedWrite()) {
                // 记下被拦下的调用：审批只认这一次，待批参数取已过绑定与契约校验的值
                request.context().scope().note(NodeNoteKey.GATED_WRITE,
                    new PendingAction(request.toolName(), request.arguments(), RuntimeUtils.argsHash(args)));
                // 写调用本身正当，须先由模型申请确认：推迟，并在提示里给出下一步与工具名
                return Optional.of(Observation.deferred(
                    RuntimeErrorCode.APPROVAL_REQUIRED,
                    ModelHint.WRITE_NEED_APPROVAL.text(),
                    ModelHint.NEXT_APPROVAL.text("tool", request.toolName())
                ));
            }
        }

        return observe(request, stateFacade.prewrite(request.context().scope(), call));
    }

    /**
     * 按预写结论放行或给出观测：Proceed 才放行；Reused / Blocked 都带观测返回，工具不会被调用。
     *
     * @param request 当前调用
     * @param result  预写结论
     * @return 复用或阻断观测；允许继续时为空
     */
    private Optional<Observation> observe(ToolInvokeRequest request, PrewriteResult result) {
        return switch (result) {
            case PrewriteResult.Proceed proceed -> {
                request.evidenceId(proceed.evidenceId());
                yield Optional.empty();
            }
            case PrewriteResult.Reused reused -> Optional.of(reusedObservation(request, reused.evidenceId()));
            case PrewriteResult.Blocked blocked -> Optional.of(blockedObservation(blocked.status()));
        };
    }

    /**
     * 已批准的写操作执行失败时，把下一步换成"向用户说明并询问如何处理"。
     *
     * <p>批准只针对那一次调用；失败后的补救是否继续、如何继续由用户决定，模型不得自行改用其它写操作。
     * 其它观测原样返回。例：确认后保存文章返回业务失败，模型收到的下一步是追问用户，而不是修正参数重试。</p>
     *
     * @param request 本次工具调用请求
     * @param result  内层拦截器处理后的观测
     * @return 原观测，或替换下一步后的观测
     */
    @Override
    public Observation after(ToolInvokeRequest request, Observation result) {
        if (result.outcome() != Outcome.FAILED || !request.context().approvedWrite()) {
            return result;
        }

        return result.withNext(ModelHint.NEXT_APPROVED_FAILED.text());
    }

    /**
     * 写闸阻断时的推迟观测：上一次同一写操作待核实或仍在进行，须等其结果。
     *
     * @param status 预写阻断状态
     * @return 待核验或进行中推迟观测
     */
    private static Observation blockedObservation(EvidenceStatus status) {
        if (status == EvidenceStatus.UNKNOWN || status == EvidenceStatus.UNKNOWN_PRE_CALL) {
            RuntimeErrorCode code = RuntimeErrorCode.WRITE_PENDING_VERIFY;
            return Observation.deferred(code, code.getMessage(), ModelHint.NEXT_WAIT_WRITE.text());
        }

        RuntimeErrorCode code = RuntimeErrorCode.WRITE_IN_FLIGHT;
        return Observation.deferred(code, code.getMessage(), ModelHint.NEXT_WAIT_WRITE.text());
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
}
