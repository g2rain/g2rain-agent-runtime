package com.g2rain.agent.runtime.engine.tools;


import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * 工具调用层错误码到失败分类与证据状态的对照表。
 *
 * <p>只做相等比较。调用层错误码必须落在本表；表外错误码视为业务失败（由 ResponseContract 写出）。</p>
 *
 * @author alpha
 * @since 2026/10/4
 */
public enum ToolErrors {

    TOOL_TIMEOUT(RuntimeErrorCode.TOOL_TIMEOUT, FailureKind.TIMEOUT, EvidenceStatus.TIMEOUT, EvidenceStatus.UNKNOWN),

    TOOL_UNREACHABLE(RuntimeErrorCode.TOOL_UNREACHABLE, FailureKind.TRANSPORT_FAILURE, EvidenceStatus.FAILED, EvidenceStatus.FAILED),

    TOOL_IO_ERROR(RuntimeErrorCode.TOOL_IO_ERROR, FailureKind.TRANSPORT_FAILURE, EvidenceStatus.FAILED, EvidenceStatus.UNKNOWN),

    TOOL_ERROR(RuntimeErrorCode.TOOL_ERROR, FailureKind.UNKNOWN, EvidenceStatus.FAILED, EvidenceStatus.UNKNOWN),

    MCP_TOOL_ERROR(RuntimeErrorCode.MCP_TOOL_ERROR, FailureKind.BUSINESS_FAILURE, EvidenceStatus.FAILED, EvidenceStatus.FAILED),

    MCP_INVALID_PARAMS(RuntimeErrorCode.MCP_INVALID_PARAMS, FailureKind.PARAMETER_FAILURE, EvidenceStatus.FAILED, EvidenceStatus.FAILED),

    MCP_RPC_ERROR(RuntimeErrorCode.MCP_RPC_ERROR, FailureKind.UNKNOWN, EvidenceStatus.FAILED, EvidenceStatus.UNKNOWN);

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
     * @param errorCode   运行时错误码
     * @param failureKind 失败分类
     * @param readStatus  读工具失败时的证据状态
     * @param writeStatus 写工具失败时的证据状态
     */
    ToolErrors(RuntimeErrorCode errorCode, FailureKind failureKind, EvidenceStatus readStatus, EvidenceStatus writeStatus) {
        this.errorCode = errorCode;
        this.failureKind = failureKind;
        this.readStatus = readStatus;
        this.writeStatus = writeStatus;
    }

    /**
     * 按错误码查找；未命中返回 null（表外=业务失败）。
     *
     * @param errorCode 观测错误码；可空
     * @return 表内项；未命中为 {@code null}
     */
    public static @Nullable ToolErrors of(@Nullable String errorCode) {
        if (Strings.isBlank(errorCode)) {
            return null;
        }

        for (ToolErrors value : values()) {
            if (Objects.equals(value.errorCode.code(), errorCode)
                || Objects.equals(value.errorCode.name(), errorCode)) {
                return value;
            }
        }

        return null;
    }

    /**
     * 存证 / 事件共用：由 Observation 推导证据状态。
     * <ul>
     *     <li>{@code ok=true} → SUCCESS</li>
     *     <li>错误码在表内 → 按表映射</li>
     *     <li>错误码不在表内 → SUCCESS（业务失败）</li>
     * </ul>
     *
     * @param obs        工具观测
     * @param sideEffect 副作用；可空，写副作用用 writeStatus
     * @return 证据状态
     */
    public static EvidenceStatus statusOf(Observation obs, @Nullable SideEffect sideEffect) {
        if (obs.ok()) {
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
     * 是否调用层失败（错误码在表内）。
     *
     * @param obs 工具观测；可空
     * @return 失败且错误码在表内
     */
    public static boolean isCallFailure(@Nullable Observation obs) {
        return Objects.nonNull(obs) && !obs.ok() && Objects.nonNull(of(obs.errorCode()));
    }

    /**
     * 是否业务失败（ok=false 且错误码不在表内）。
     *
     * @param obs 工具观测；可空
     * @return 失败且错误码不在表内
     */
    public static boolean isBusinessFailure(@Nullable Observation obs) {
        return Objects.nonNull(obs) && !obs.ok() && Objects.isNull(of(obs.errorCode()));
    }

    /**
     * 失败分类；未知码为 {@link FailureKind#UNKNOWN}。
     *
     * @param errorCode 观测错误码；可空
     * @return 失败分类
     */
    public static FailureKind failureKindOf(@Nullable String errorCode) {
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

    /**
     * @return 读工具超时时的证据状态
     */
    public EvidenceStatus readStatus() {
        return readStatus;
    }

    /**
     * @return 写工具超时时的证据状态
     */
    public EvidenceStatus writeStatus() {
        return writeStatus;
    }
}
