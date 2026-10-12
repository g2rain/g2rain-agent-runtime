package com.g2rain.agent.runtime.engine.state.repo;

import com.g2rain.agent.runtime.converter.ToolCallRecordConverter;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.agent.runtime.dto.ToolCallRecordDto;
import com.g2rain.agent.runtime.dto.ToolCallRecordSelectDto;
import com.g2rain.agent.runtime.service.ToolCallRecordService;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.vo.ToolCallRecordVo;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具调用仓储：{@code tool_call_record} 的读写，证据头的冷存储。
 *
 * @author alpha
 * @since 2026/10/12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallRepository {

    /**
     * 工具调用结果摘要与错误信息的长度上限（列宽 1000）。
     */
    private static final int CALL_SUMMARY_MAX = 1000;

    /**
     * 错误码列宽上限。
     */
    private static final int ERROR_CODE_MAX = 64;

    /**
     * 工具调用记录表服务。
     */
    private final ToolCallRecordService toolCallRecordService;

    /**
     * 按 callId 查工具调用行。
     *
     * @param callId 证据 / 调用 id
     * @return 行；没有为 {@code null}
     */
    public @Nullable ToolCallRecordVo find(String callId) {
        ToolCallRecordSelectDto query = new ToolCallRecordSelectDto();
        query.setCallId(callId);
        List<ToolCallRecordVo> list = toolCallRecordService.selectList(query);
        return Collections.isEmpty(list) ? null : list.getFirst();
    }

    /**
     * 调用记录上保存的响应报文。
     *
     * @param callId 调用 id
     * @return 响应 JSON；没有为 {@code null}
     */
    public @Nullable String response(String callId) {
        ToolCallRecordVo row = find(callId);
        return Objects.isNull(row) || Strings.isBlank(row.getResponse()) ? null : row.getResponse();
    }

    /**
     * Redis 证据 Hash 为空时，从 {@code tool_call_record} 回灌本节点摘要。
     *
     * @param taskId 任务 id
     * @param nodeId 节点 id
     * @return 证据头列表；参数空或没有行为空列表
     */
    public List<EvidenceHead> loadNodeEvidence(String taskId, String nodeId) {
        if (Strings.isBlank(taskId) || Strings.isBlank(nodeId)) {
            return List.of();
        }
        try {
            ToolCallRecordSelectDto query = new ToolCallRecordSelectDto();
            query.setTaskId(taskId);
            query.setNodeId(nodeId);
            List<ToolCallRecordVo> list = toolCallRecordService.selectList(query);
            if (Collections.isEmpty(list)) {
                return List.of();
            }
            List<EvidenceHead> heads = new ArrayList<>(list.size());
            for (ToolCallRecordVo vo : list) {
                EvidenceHead head = toHead(vo);
                if (Objects.nonNull(head)) {
                    heads.add(head);
                }
            }
            return heads;
        } catch (Exception e) {
            log.warn("[CallRepository] loadNodeEvidence failed taskId={} nodeId={}", taskId, nodeId, e);
            throw RepoSupport.unavailable(e);
        }
    }

    /**
     * Redis writes 索引未命中时，按任务 + 工具 + 幂等键查有效写证据。
     *
     * @param taskId   任务 id
     * @param toolName 工具名
     * @param key      幂等键
     * @return 仍占用幂等槽的写证据；没有为空 Optional
     */
    public Optional<EvidenceHead> findLiveWrite(String taskId, String toolName, String key) {
        if (Strings.isBlank(taskId) || Strings.isBlank(toolName) || Strings.isBlank(key)) {
            return Optional.empty();
        }
        try {
            ToolCallRecordSelectDto query = new ToolCallRecordSelectDto();
            query.setTaskId(taskId);
            query.setToolName(toolName);
            query.setIdempotencyKey(key);
            List<ToolCallRecordVo> list = toolCallRecordService.selectList(query);
            if (Collections.isEmpty(list)) {
                return Optional.empty();
            }
            for (ToolCallRecordVo vo : list) {
                EvidenceHead head = toHead(vo);
                if (Objects.nonNull(head) && isLiveWrite(head)) {
                    return Optional.of(head);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[CallRepository] findLiveWrite failed taskId={} tool={}", taskId, toolName, e);
            throw RepoSupport.unavailable(e);
        }
    }

    /**
     * 为即将执行的工具调用插入 STARTED 记录；错误信息按列宽截断，失败直接向上抛出。
     *
     * @param dto 已填必填字段的 DTO
     */
    public void insertStarted(ToolCallRecordDto dto) {
        dto.setErrorMessage(RuntimeUtils.clip(dto.getErrorMessage(), CALL_SUMMARY_MAX));
        toolCallRecordService.save(dto);
    }

    /**
     * 更新调用状态与可选响应。
     *
     * @param callId    调用 id
     * @param status    新状态
     * @param response  响应 JSON；可空
     * @param summary   结果摘要；可空
     * @param errorCode 错误码；可空
     */
    public void update(String callId, String status, @Nullable String response, @Nullable String summary, @Nullable String errorCode) {
        ToolCallRecordVo existing = find(callId);
        if (Objects.isNull(existing)) {
            return;
        }
        ToolCallRecordDto dto = ToolCallRecordConverter.INSTANCE.vo2dto(existing);
        dto.setCallStatus(status);
        dto.setResponse(response);
        dto.setResultSummary(RuntimeUtils.clip(summary, CALL_SUMMARY_MAX));
        dto.setErrorCode(RuntimeUtils.clip(errorCode, ERROR_CODE_MAX));
        toolCallRecordService.save(dto);
    }

    /**
     * 按证据摘要更新调用行（含业务语义字段）。
     *
     * @param head     证据头（状态、摘要、业务成败等）
     * @param response 响应 JSON；可空
     */
    public void update(EvidenceHead head, @Nullable String response) {
        update(head, response, null);
    }

    /**
     * 按证据摘要更新调用行，并写入工具实际执行的耗时。
     *
     * @param head       证据头（状态、摘要、业务成败等）
     * @param response   响应 JSON；可空
     * @param durationMs 耗时毫秒；为空时保留原值
     */
    public void update(EvidenceHead head, @Nullable String response, @Nullable Long durationMs) {
        ToolCallRecordVo existing = find(head.evidenceId());
        if (Objects.isNull(existing)) {
            return;
        }
        ToolCallRecordDto dto = ToolCallRecordConverter.INSTANCE.vo2dto(existing);
        dto.setCallStatus(head.status().name());
        dto.setResponse(response);
        dto.setResultSummary(RuntimeUtils.clip(head.resultBrief(), CALL_SUMMARY_MAX));
        dto.setErrorCode(RuntimeUtils.clip(head.errorCode(), ERROR_CODE_MAX));
        dto.setErrorMessage(RuntimeUtils.clip(head.detail(), CALL_SUMMARY_MAX));
        if (Objects.nonNull(head.businessOk())) {
            dto.setBusinessOk(head.businessOk() ? (byte) 1 : (byte) 0);
        }
        dto.setEmptyResult(Boolean.TRUE.equals(head.empty()) ? (byte) 1 : (byte) 0);
        FailureKind kind = head.failureKind();
        dto.setFailureKind(kind.name());
        dto.setEntities(head.entities());
        dto.setPayloadId(head.payloadId());
        if (Objects.nonNull(durationMs)) {
            dto.setDurationMs(durationMs);
        }
        toolCallRecordService.save(dto);
    }

    /**
     * 轮末把证据头状态追到调用记录；没有记录的证据跳过。
     *
     * @param heads 证据头
     */
    public void syncHeads(Collection<EvidenceHead> heads) {
        heads.forEach(head -> {
            ToolCallRecordVo existing = find(head.evidenceId());
            if (Objects.isNull(existing)) {
                return;
            }
            update(head, existing.getResponse());
        });
    }

    /**
     * 调用记录行转证据头。
     *
     * @param vo 表行
     * @return 证据头；缺 callId 为 {@code null}
     */
    private @Nullable EvidenceHead toHead(ToolCallRecordVo vo) {
        if (Strings.isBlank(vo.getCallId())) {
            return null;
        }
        EvidenceStatus status;
        try {
            status = Strings.isBlank(vo.getCallStatus()) ? EvidenceStatus.STARTED : EvidenceStatus.valueOf(vo.getCallStatus());
        } catch (IllegalArgumentException e) {
            status = EvidenceStatus.STARTED;
        }
        SideEffect sideEffect = SideEffect.NONE;
        if (Strings.isNotBlank(vo.getSideEffect())) {
            try {
                sideEffect = SideEffect.valueOf(vo.getSideEffect());
            } catch (IllegalArgumentException ignored) {

            }
        }
        FailureKind kind = FailureKind.NONE;
        if (Strings.isNotBlank(vo.getFailureKind())) {
            try {
                kind = FailureKind.valueOf(vo.getFailureKind());
            } catch (IllegalArgumentException ignored) {

            }
        }
        int round = Objects.isNull(vo.getRound()) ? 0 : vo.getRound();
        return new EvidenceHead(vo.getCallId(), vo.getToolName(), sideEffect, status, flag(vo.getBusinessOk()),
            flag(vo.getEmptyResult()), null, kind, vo.getIdempotencyKey(), vo.getArgsHash(), null,
            vo.getResultSummary(), vo.getErrorCode(), vo.getEntities(), vo.getPayloadId(), round,
            Objects.toString(vo.getTraceId(), ""), vo.getErrorMessage());
    }

    /**
     * 字节标志转布尔。
     *
     * @param value 库字段；可空
     * @return {@code null} 保持空；非 0 为 {@code true}
     */
    private static @Nullable Boolean flag(@Nullable Byte value) {
        if (Objects.isNull(value)) {
            return null;
        }
        return value != 0;
    }

    /**
     * 写证据是否仍占用幂等槽。
     *
     * @param head 证据头
     * @return STARTED / UNKNOWN / 算成功的 SUCCESS 为 {@code true}
     */
    private static boolean isLiveWrite(EvidenceHead head) {
        EvidenceStatus status = head.status();
        if (status == EvidenceStatus.SUCCESS) {
            return !Boolean.FALSE.equals(head.businessOk());
        }
        return status == EvidenceStatus.STARTED
            || status == EvidenceStatus.UNKNOWN
            || status == EvidenceStatus.UNKNOWN_PRE_CALL;
    }
}
