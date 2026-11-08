package com.g2rain.agent.runtime.engine.state;



import lombok.extern.slf4j.Slf4j;
import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.tool.ToolCall;
import com.g2rain.agent.runtime.domain.tool.ToolResult;
import com.g2rain.agent.runtime.dto.ToolCallRecordDto;
import com.g2rain.agent.runtime.engine.state.repo.CallRepository;
import com.g2rain.agent.runtime.engine.state.repo.PayloadRepository;
import com.g2rain.agent.runtime.engine.store.StateStore;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.ArgsBrief;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.agent.runtime.vo.ToolCallRecordVo;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContextHolder;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * 写工具证据的预登记、结算与非写调用记录。
 *
 * <p>热态 Redis 先更新，再写 {@code tool_call_record}。调用方经 {@link RuntimeStateFacade}
 * 使用本类，不要直接绕过预写去调写工具。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Component
@Slf4j
public class EvidenceFlow {

    /**
     * 证据报文与请求体的 JSON 编解码器。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 工具调用记录落库。
     */
    private final CallRepository calls;

    /**
     * 大报文落库。
     */
    private final PayloadRepository payloads;

    /**
     * 把节点证据改动刷入 Redis。
     */
    private final TurnFlushPlan flushPlan;

    /**
     * 摘要截断与报文内联上限。
     */
    private final RuntimeProperties properties;

    /**
     * 读任务级写证据索引。
     */
    private final StateStore store;

    /**
     * @param calls       工具调用仓储
     * @param payloads    报文仓储
     * @param flushPlan   热态刷新
     * @param properties  摘要与报文上限
     * @param store       热态
     */
    public EvidenceFlow(CallRepository calls, PayloadRepository payloads, TurnFlushPlan flushPlan,
                        RuntimeProperties properties, StateStore store) {
        this.calls = calls;
        this.payloads = payloads;
        this.flushPlan = flushPlan;
        this.properties = properties;
        this.store = store;
    }

    /**
     * 为写工具登记 STARTED 证据；已有生效证据则复用或阻断。
     *
     * @param node 当前节点
     * @param call 写工具调用
     * @return {@link PrewriteResult.Proceed} 才允许真正调工具
     */
    public PrewriteResult prewrite(DefaultNodeScope node, ToolCall call) {
        Optional<PrewriteResult> existing = existing(node, call);
        if (existing.isPresent()) {
            return existing.get();
        }
        EvidenceHead started = startedOf(node, call);
        node.addEvidence(started);
        // STARTED 必须先落热态再插库，真正回调发生在 Proceed 之后
        flushPlan.apply(node.turn(), false);
        try {
            calls.insertStarted(startedDto(node, call, started));
            return new PrewriteResult.Proceed(started.evidenceId());
        } catch (TransientDataAccessException e) {
            log.error("failed to prewrite", e);
            // 库表瞬时失败：热态改 UNKNOWN_PRE_CALL，禁止本轮再调同一写动作
            return unknownPreCall(node, started);
        } catch (DataAccessException e) {
            log.error("failed to prewrite", e);
            // 库表永久失败：热态改 CANCELLED，允许换键或稍后重试，不把 STARTED 留成幽灵
            EvidenceHead cancelled = started.withStatus(EvidenceStatus.CANCELLED, FailureKind.UNKNOWN, null);
            node.addEvidence(cancelled);
            flushPlan.apply(node.turn(), false);
            throw new BusinessException(RuntimeErrorCode.STATE_WRITE_FAILED);
        } catch (RuntimeException e) {
            log.error("failed to prewrite", e);
            return unknownPreCall(node, started);
        }
    }

    /**
     * 任务内是否已有同一写操作的生效证据：算成功则复用，STARTED / UNKNOWN* 则阻断；不登记新证据。
     *
     * @param node 当前节点
     * @param call 写工具调用
     * @return 复用或阻断结论；没有生效证据为空
     */
    public Optional<PrewriteResult> existing(DefaultNodeScope node, ToolCall call) {
        return findLiveEvidence(node, call.toolName(), liveKey(call)).map(head -> {
            boolean succeeded = Objects.equals(head.status(), EvidenceStatus.SUCCESS)
                && !Boolean.FALSE.equals(head.businessOk());
            return succeeded
                ? new PrewriteResult.Reused(head.evidenceId())
                : new PrewriteResult.Blocked(head.evidenceId(), head.status());
        });
    }

    /**
     * 任务内同工具 + 生效幂等键的有效证据（STARTED / 算成功的 SUCCESS / UNKNOWN / UNKNOWN_PRE_CALL）。
     * FAILED / CANCELLED / SUCCESS 且 businessOk=false 不命中，允许重试。
     * <p>先看本节点，再按写索引 HGET {@code task:writes}，不打开其他节点空间。</p>
     *
     * @param node     当前节点
     * @param toolName 工具名
     * @param key      幂等键
     * @return 仍生效的证据；没有则为空
     */
    private Optional<EvidenceHead> findLiveEvidence(DefaultNodeScope node, String toolName, String key) {
        Optional<EvidenceHead> local = node.evidence().stream()
            .filter(head -> isLiveDuplicate(head, toolName, key))
            .findFirst();
        if (local.isPresent()) {
            return local;
        }
        // 本节点没有再查任务写索引，避免打开全图节点空间
        String taskId = node.turn().task().map(TaskMetadata::getTaskId).orElse(null);
        if (Strings.isBlank(taskId)) {
            return Optional.empty();
        }
        SessionKeys keys = SessionKeys.of(node.turn());
        String raw;
        try {
            raw = store.hGet(keys.writes(taskId), WriteIndexEntry.field(toolName, key));
        } catch (RuntimeException e) {
            log.error("failed to findLiveEvidence", e);
            throw new BusinessException(RuntimeErrorCode.STATE_UNAVAILABLE);
        }
        WriteIndexEntry entry = JSON.str2obj(raw, WriteIndexEntry.class);
        if (Objects.nonNull(entry) && entry.live() && Strings.isNotBlank(entry.evidenceId())) {
            return Optional.of(new EvidenceHead(entry.evidenceId(), toolName, SideEffect.WRITE, entry.status(),
                entry.businessOk(), null, null, FailureKind.NONE, key, null, null, null, null, null, null, 0, "", null));
        }
        return calls.findLiveWrite(taskId, toolName, key);
    }

    /**
     * 工具调用前登记超时或状态未知时，将 Redis 标记为 UNKNOWN_PRE_CALL，并尽量把 MySQL 追成同一状态。
     *
     * @param node    当前节点
     * @param started 已写入的 STARTED 证据
     * @return 阻断预写结果
     */
    private PrewriteResult unknownPreCall(DefaultNodeScope node, EvidenceHead started) {
        EvidenceHead unknown = started.withStatus(EvidenceStatus.UNKNOWN_PRE_CALL, FailureKind.UNKNOWN, null);
        node.addEvidence(unknown);
        flushPlan.apply(node.turn(), false);
        try {
            calls.update(unknown, null);
        } catch (RuntimeException e) {
            log.error("failed to unknownPreCall", e);
            // 保留 Redis；轮末 persistTurn 按 call_id 再追
            Trace.at(TraceStage.STATE, "call_update_deferred")
                .kv("evidenceId", unknown.evidenceId())
                .kv("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                .warn();
        }
        return new PrewriteResult.Blocked(started.evidenceId(), EvidenceStatus.UNKNOWN_PRE_CALL);
    }

    /**
     * 本节点证据是否与即将发起的写调用重复且仍生效。
     *
     * @param head     已有证据
     * @param toolName 工具名
     * @param key      幂等键
     * @return 命中仍生效的重复写
     */
    private static boolean isLiveDuplicate(EvidenceHead head, String toolName, String key) {
        if (!Objects.equals(head.toolName(), toolName) || !Objects.equals(head.idempotencyKey(), key)) {
            return false;
        }
        EvidenceStatus status = head.status();
        if (status == EvidenceStatus.SUCCESS) {
            // 算成功 = SUCCESS 且 businessOk 不为 false；业务失败等同 FAILED，允许重试
            return !Boolean.FALSE.equals(head.businessOk());
        }
        return status == EvidenceStatus.STARTED
            || status == EvidenceStatus.UNKNOWN
            || status == EvidenceStatus.UNKNOWN_PRE_CALL;
    }

    /**
     * 把写工具结果结算到已登记证据，并按大小分流报文。
     *
     * @param node       当前节点
     * @param evidenceId 预写返回的证据 id
     * @param result     工具结果
     * @throws BusinessException 找不到对应证据
     */
    public void settle(DefaultNodeScope node, String evidenceId, ToolResult result) {
        EvidenceHead current = node.evidence().stream()
            .filter(head -> Objects.equals(head.evidenceId(), evidenceId))
            .findFirst()
            .orElseThrow(() -> new BusinessException(RuntimeErrorCode.STATE_WRITE_FAILED));
        String entities = Strings.isNotBlank(result.entities()) ? result.entities() : current.entities();
        EvidenceHead settled = current.withResult(result, clipBrief(result.resultBrief()), entities,
            detailOf(result));
        persistPayload(node, settled, result);
        persistCallBody(node, settled, result);
    }

    /**
     * 记录一次真实发起的非写（或拒绝）工具调用。
     *
     * @param node   当前节点
     * @param call   工具调用
     * @param result 调用结果
     * @return 新证据 id
     */
    public String record(DefaultNodeScope node, ToolCall call, ToolResult result) {
        EvidenceHead head = startedOf(node, call)
            .withResult(result, clipBrief(result.resultBrief()), result.entities(), detailOf(result));
        persistPayload(node, head, result);
        try {
            String body = json(result.payload());
            boolean oversize = oversized(body);
            ToolCallRecordDto dto = startedDto(node, call, head);
            dto.setCallStatus(result.status().name());
            dto.setResponse(oversize ? null : body);
            dto.setResultSummary(head.resultBrief());
            dto.setErrorCode(result.errorCode());
            dto.setDurationMs(result.durationMs());
            calls.insertStarted(dto);
            if (oversize) {
                String taskId = node.turn().task().map(TaskMetadata::getTaskId).orElse(null);
                if (Strings.isNotBlank(taskId) && Strings.isNotBlank(body)) {
                    payloads.saveArtifact(head.evidenceId(), taskId, node.nodeId(), head.toolName(),
                        node.turn().traceId(), body);
                }
            }
        } catch (RuntimeException e) {
            log.error("failed to record", e);
            throw new BusinessException(RuntimeErrorCode.STATE_WRITE_FAILED);
        }
        return head.evidenceId();
    }

    /**
     * 打开节点时规整调用前未知证据：库里有记录则两边改为 CANCELLED。
     *
     * @param head {@link EvidenceStatus#UNKNOWN_PRE_CALL} 摘要
     * @return 更新后的 CANCELLED 摘要
     */
    public EvidenceHead cancelPreCall(EvidenceHead head) {
        ToolCallRecordVo row = calls.find(head.evidenceId());
        EvidenceHead cancelled = head.withStatus(EvidenceStatus.CANCELLED, FailureKind.UNKNOWN, null);
        if (Objects.nonNull(row)) {
            calls.update(head.evidenceId(), EvidenceStatus.CANCELLED.name(), row.getResponse(),
                row.getResultSummary(), row.getErrorCode());
        }
        return cancelled;
    }

    /**
     * 用户确认/拒绝或回查工具把未知写证据收到指定状态。
     *
     * @param node       当前节点
     * @param current    待收口证据
     * @param status     目标证据状态
     * @param businessOk 业务是否成功；可空
     * @param kind       失败分类
     */
    public void applyOutcome(DefaultNodeScope node, EvidenceHead current, EvidenceStatus status,
                             @Nullable Boolean businessOk, FailureKind kind) {
        EvidenceHead next = current.withOutcome(status, businessOk, kind);
        node.addEvidence(next);
        flushPlan.apply(node.turn(), false);
        ToolCallRecordVo row = calls.find(current.evidenceId());
        calls.update(next, Objects.isNull(row) ? null : row.getResponse());
    }

    /**
     * 写工具原始请求 JSON，供 verify.args 绑定 {@code call.*}。
     *
     * @param evidenceId 证据 id
     * @return 请求体；库中无记录为 {@code null}
     */
    public @Nullable String requestJson(String evidenceId) {
        ToolCallRecordVo row = calls.find(evidenceId);
        return Objects.isNull(row) ? null : row.getRequest();
    }

    /**
     * 按本次调用拼一条 STARTED 证据头：新证据 id、入参摘要与定位信息，尚无结果。
     *
     * @param node 当前节点
     * @param call 工具调用
     * @return 新证据头
     */
    private EvidenceHead startedOf(DefaultNodeScope node, ToolCall call) {
        String argsBrief = ArgsBrief.of(call.args(), call.contract(), properties.getReact().getBriefMaxChars());
        return new EvidenceHead(RuntimeUtils.uuid(), call.toolName(), call.sideEffect(), EvidenceStatus.STARTED,
            null, null, null, FailureKind.NONE, liveKey(call), RuntimeUtils.argsHash(call.args()), argsBrief,
            null, null, null, null, node.rounds(), node.turn().traceId(), null);
    }

    /**
     * 未成功调用的完整说明：执行失败或业务失败时取观测说明，按观测错误上限裁剪；成功为 {@code null}。
     *
     * @param result 工具结果
     * @return 完整说明；成功为 {@code null}
     */
    private @Nullable String detailOf(ToolResult result) {
        boolean succeeded = result.status() == EvidenceStatus.SUCCESS && !Boolean.FALSE.equals(result.businessOk());
        if (succeeded) {
            return null;
        }

        return RuntimeUtils.clip(result.resultBrief(), properties.getReact().getObservationErrorMaxChars());
    }

    /**
     * 按 React 摘要上限裁剪结果简述。
     *
     * @param text 原文；可空
     * @return 裁剪后文本；空白入参原样返回
     */
    private @Nullable String clipBrief(@Nullable String text) {
        return RuntimeUtils.clip(text, properties.getReact().getBriefMaxChars());
    }

    /**
     * 组装即将插入库表的 STARTED 调用行。
     *
     * @param node 当前节点
     * @param call 工具调用
     * @param head 证据头
     * @return 调用记录 DTO
     */
    private ToolCallRecordDto startedDto(DefaultNodeScope node, ToolCall call, EvidenceHead head) {
        ToolCallRecordDto dto = new ToolCallRecordDto();
        dto.setOrganId(PrincipalContextHolder.getOrganId());
        dto.setTaskId(node.turn().task().map(TaskMetadata::getTaskId).orElse(null));
        dto.setNodeId(node.nodeId());
        dto.setCallId(head.evidenceId());
        dto.setToolName(call.toolName());
        dto.setSideEffect(call.sideEffect().name());
        dto.setArgsHash(Objects.requireNonNullElse(head.argsHash(), RuntimeUtils.argsHash(call.args())));
        dto.setIdempotencyKey(Objects.requireNonNullElse(head.idempotencyKey(), dto.getArgsHash()));
        dto.setRound(head.round());
        dto.setCallStatus(head.status().name());
        dto.setTraceId(node.turn().traceId());
        dto.setRequest(json(call.args()));
        dto.setResultSummary(head.resultBrief());
        dto.setErrorCode(head.errorCode());
        dto.setErrorMessage(head.detail());
        dto.setEmptyResult(Boolean.TRUE.equals(head.empty()) ? (byte) 1 : (byte) 0);
        dto.setFailureKind(head.failureKind().name());
        if (Objects.nonNull(head.businessOk())) {
            dto.setBusinessOk(head.businessOk() ? (byte) 1 : (byte) 0);
        }
        dto.setEntities(head.entities());
        dto.setPayloadId(head.payloadId());
        return dto;
    }

    /**
     * Redis 始终写完整报文；超限时 EvidenceHead.payloadId = evidenceId。
     *
     * @param node   当前节点
     * @param head   证据头
     * @param result 工具结果
     */
    private void persistPayload(DefaultNodeScope node, EvidenceHead head, ToolResult result) {
        String body = json(result.payload());
        EvidenceHead stored = oversized(body) ? head.withPayloadId(head.evidenceId()) : head;
        node.addEvidence(stored);
        node.turn().putPayload(stored.evidenceId(), result.payload());
        flushPlan.apply(node.turn(), false);
    }

    /**
     * 不超过上限进 {@code tool_call_record.response}；超过写 ARTIFACT，response 留空。
     *
     * @param node   当前节点
     * @param head   证据头
     * @param result 工具结果
     */
    private void persistCallBody(DefaultNodeScope node, EvidenceHead head, ToolResult result) {
        String body = json(result.payload());
        if (!oversized(body)) {
            calls.update(head, body, result.durationMs());
            return;
        }
        calls.update(head, null, result.durationMs());
        String taskId = node.turn().task().map(TaskMetadata::getTaskId).orElse(null);
        if (Strings.isBlank(taskId) || Strings.isBlank(body)) {
            return;
        }
        payloads.saveArtifact(head.evidenceId(), taskId, node.nodeId(), head.toolName(),
            node.turn().traceId(), body);
    }

    /**
     * 报文是否超过内联字节上限。
     *
     * @param body JSON 文本；可空
     * @return 超限为 {@code true}；空白为 {@code false}
     */
    private boolean oversized(@Nullable String body) {
        if (Strings.isBlank(body)) {
            return false;
        }
        return body.getBytes(StandardCharsets.UTF_8).length > properties.getState().getPayloadInlineMaxBytes();
    }

    /**
     * 写调用幂等键：契约键优先，否则工具名加参数哈希。
     *
     * @param call 工具调用
     * @return 非空幂等键
     */
    private static String liveKey(ToolCall call) {
        String configured = call.idempotencyKey();
        if (Strings.isNotBlank(configured)) {
            return configured;
        }
        String tool = Strings.defaultIfBlank(call.toolName(), "");
        String hash = RuntimeUtils.argsHash(call.args());
        String material = Strings.isBlank(tool) ? hash : tool + "|" + hash;
        return RuntimeUtils.sha256Hex(material.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * JsonNode 转存储文本。
     *
     * @param node JSON 节点；可空
     * @return JSON 文本；null 节点为 {@code null}
     */
    private static @Nullable String json(@Nullable JsonNode node) {
        if (Objects.isNull(node) || node.isNull()) {
            return null;
        }
        return JSON.obj2str(node);
    }

    /**
     * 非 WRITE 副作用不得走预写。
     *
     * @param call 待校验调用
     * @throws BusinessException 副作用不是 WRITE
     */
    public static void requireWrite(ToolCall call) {
        if (!Objects.equals(call.sideEffect(), SideEffect.WRITE)) {
            throw new BusinessException(RuntimeErrorCode.STATE_WRITE_FAILED);
        }
    }
}
