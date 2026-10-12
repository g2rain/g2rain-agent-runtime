package com.g2rain.agent.runtime.engine.tools;


import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.Outcome;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * 工具调用层错误码到失败分类、证据状态与模型下一步的对照表。
 *
 * <p>调用层错误码必须落在本表；表外错误码视为业务失败（由 ResponseContractInterceptor 写出）。
 * 例：{@code ToolErrors.TOOL_TIMEOUT.observe("read timed out")} 得到带超时下一步的失败观测。</p>
 *
 * @author alpha
 * @since 2026/10/4
 */
public enum ToolErrors {

    /**
     * 调用超时：读工具记 TIMEOUT，写工具结果未知记 UNKNOWN，提示模型按超时处理。
     */
    TOOL_TIMEOUT(RuntimeErrorCode.TOOL_TIMEOUT, FailureKind.TIMEOUT, EvidenceStatus.TIMEOUT, EvidenceStatus.UNKNOWN, ModelHint.NEXT_TIMEOUT),

    /**
     * 工具不可达：请求未发出，读写都记 FAILED，提示模型可重试。
     */
    TOOL_UNREACHABLE(RuntimeErrorCode.TOOL_UNREACHABLE, FailureKind.TRANSPORT_FAILURE, EvidenceStatus.FAILED, EvidenceStatus.FAILED, ModelHint.NEXT_RETRY),

    /**
     * 传输中断：读工具记 FAILED，写工具可能已生效记 UNKNOWN，提示模型按超时处理。
     */
    TOOL_IO_ERROR(RuntimeErrorCode.TOOL_IO_ERROR, FailureKind.TRANSPORT_FAILURE, EvidenceStatus.FAILED, EvidenceStatus.UNKNOWN, ModelHint.NEXT_TIMEOUT),

    /**
     * 未分类的调用异常：读工具记 FAILED，写工具记 UNKNOWN，提示模型可重试。
     */
    TOOL_ERROR(RuntimeErrorCode.TOOL_ERROR, FailureKind.UNKNOWN, EvidenceStatus.FAILED, EvidenceStatus.UNKNOWN, ModelHint.NEXT_RETRY),

    /**
     * MCP 工具返回错误结果：按业务失败处理，读写都记 FAILED。
     */
    MCP_TOOL_ERROR(RuntimeErrorCode.MCP_TOOL_ERROR, FailureKind.BUSINESS_FAILURE, EvidenceStatus.FAILED, EvidenceStatus.FAILED, ModelHint.NEXT_BUSINESS),

    /**
     * MCP 参数校验失败：读写都记 FAILED，提示模型修正入参。
     */
    MCP_INVALID_PARAMS(RuntimeErrorCode.MCP_INVALID_PARAMS, FailureKind.PARAMETER_FAILURE, EvidenceStatus.FAILED, EvidenceStatus.FAILED, ModelHint.NEXT_FIX_ARGS),

    /**
     * MCP 协议层错误：读工具记 FAILED，写工具记 UNKNOWN，提示模型可重试。
     */
    MCP_RPC_ERROR(RuntimeErrorCode.MCP_RPC_ERROR, FailureKind.UNKNOWN, EvidenceStatus.FAILED, EvidenceStatus.UNKNOWN, ModelHint.NEXT_RETRY);

    /**
     * 对应的运行时错误码。
     */
    private final RuntimeErrorCode errorCode;

    /**
     * 失败分类。
     */
    private final FailureKind failureKind;

    /**
     * 读工具失败时的证据状态。
     */
    private final EvidenceStatus readStatus;

    /**
     * 写工具失败时的证据状态。
     */
    private final EvidenceStatus writeStatus;

    /**
     * 给模型的下一步。
     */
    private final ModelHint next;

    /**
     * @param errorCode   运行时错误码
     * @param failureKind 失败分类
     * @param readStatus  读工具失败时的证据状态
     * @param writeStatus 写工具失败时的证据状态
     * @param next        给模型的下一步
     */
    ToolErrors(RuntimeErrorCode errorCode, FailureKind failureKind, EvidenceStatus readStatus,
               EvidenceStatus writeStatus, ModelHint next) {
        this.errorCode = errorCode;
        this.failureKind = failureKind;
        this.readStatus = readStatus;
        this.writeStatus = writeStatus;
        this.next = next;
    }

    /**
     * 按错误码查找；未命中返回 null（表外=业务失败）。
     *
     * @param errorCode 观测错误码；可空
     * @return 表内项；未命中为 {@code null}
     */
    public static @Nullable ToolErrors of(@Nullable RuntimeErrorCode errorCode) {
        if (Objects.isNull(errorCode)) {
            return null;
        }

        for (ToolErrors value : values()) {
            if (value.errorCode == errorCode) {
                return value;
            }
        }

        return null;
    }

    /**
     * 按本项生成执行失败观测，下一步取本项配置。
     *
     * @param reason 失败原因
     * @return 执行失败观测
     */
    public Observation observe(String reason) {
        return Observation.failed(errorCode, reason, next.text());
    }

    /**
     * 存证 / 事件共用：由 Observation 推导证据状态。
     * <ul>
     *     <li>结论 {@code SUCCESS} → SUCCESS</li>
     *     <li>错误码在表内 → 按表映射</li>
     *     <li>错误码不在表内 → SUCCESS（业务失败：调用层已成功）</li>
     * </ul>
     *
     * <p>只用于实际执行过的调用（{@code SUCCESS} / {@code FAILED}）；执行前被拦下的调用不进入证据结算。</p>
     *
     * @param obs        工具观测
     * @param sideEffect 副作用；可空，写副作用用 writeStatus
     * @return 证据状态
     */
    public static EvidenceStatus statusOf(Observation obs, @Nullable SideEffect sideEffect) {
        if (obs.outcome() == Outcome.SUCCESS) {
            return EvidenceStatus.SUCCESS;
        }

        ToolErrors mapped = of(obs.errorCode());
        if (Objects.isNull(mapped)) {
            return EvidenceStatus.SUCCESS;
        }

        boolean write = sideEffect == SideEffect.WRITE;
        return write ? mapped.writeStatus : mapped.readStatus;
    }

    /**
     * 是否调用层失败（执行失败且错误码在表内）。
     *
     * @param obs 工具观测；可空
     * @return 执行失败且错误码在表内
     */
    public static boolean isCallFailure(@Nullable Observation obs) {
        return Objects.nonNull(obs) && obs.outcome() == Outcome.FAILED && Objects.nonNull(of(obs.errorCode()));
    }

    /**
     * 失败分类；未知码为 {@link FailureKind#UNKNOWN}。
     *
     * @param errorCode 观测错误码；可空
     * @return 失败分类
     */
    public static FailureKind failureKindOf(@Nullable RuntimeErrorCode errorCode) {
        ToolErrors mapped = of(errorCode);
        return Objects.isNull(mapped) ? FailureKind.UNKNOWN : mapped.failureKind;
    }

    /**
     * @return 对应运行时错误码
     */
    public RuntimeErrorCode errorCode() {
        return errorCode;
    }

    /**
     * @return 失败分类
     */
    public FailureKind failureKind() {
        return failureKind;
    }
}
