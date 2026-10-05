package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.tool.ToolCall;
import com.g2rain.agent.runtime.domain.tool.ToolResult;
import com.g2rain.agent.runtime.dto.ToolCallRecordDto;
import com.g2rain.agent.runtime.engine.store.StateStore;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.ArgsBrief;
import com.g2rain.agent.runtime.support.RuntimeUtils;
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
public class EvidenceFlow {

    /**
     * 证据报文与请求体的 JSON 编解码器。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 工具调用记录与大报文落库。
     */
    private final RuntimeStatePersistence persistence;

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
     * @param persistence 冷存储
     * @param flushPlan   热态刷新
     * @param properties  摘要与报文上限
     * @param store       热态
     */
    public EvidenceFlow(RuntimeStatePersistence persistence, TurnFlushPlan flushPlan, RuntimeProperties properties,
                        StateStore store) {
        this.persistence = persistence;
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
        String key = liveKey(call);
        Optional<EvidenceHead> existing = findLiveEvidence(node, call.toolName(), key);
        if (existing.isPresent()) {
            EvidenceHead head = existing.get();
            // 算成功才复用；STARTED / UNKNOWN* → Blocked
            if (Objects.equals(head.status(), EvidenceStatus.SUCCESS)
                && !Boolean.FALSE.equals(head.businessOk())) {
                return new PrewriteResult.Reused(head.evidenceId());
            }
            return new PrewriteResult.Blocked(head.evidenceId(), head.status());
        }
        EvidenceHead started = headOf(node, call, EvidenceStatus.STARTED, null, FailureKind.NONE, null, null);
        node.addEvidence(started);
        // STARTED 必须先落热态再插库，真正回调发生在 Proceed 之后
        flushPlan.apply(node.turn(), false);
        try {
            persistence.insertStartedCall(startedDto(node, call, started));
            return new PrewriteResult.Proceed(started.evidenceId());
        } catch (TransientDataAccessException e) {
            // 库表瞬时失败：热态改 UNKNOWN_PRE_CALL，禁止本轮再调同一写动作
            return unknownPreCall(node, started);
        } catch (DataAccessException e) {
            // 库表永久失败：热态改 CANCELLED，允许换键或稍后重试，不把 STARTED 留成幽灵
            EvidenceHead cancelled = withStatus(started, EvidenceStatus.CANCELLED, FailureKind.UNKNOWN, null);
            node.addEvidence(cancelled);
            flushPlan.apply(node.turn(), false);
            throw new BusinessException(RuntimeErrorCode.STATE_WRITE_FAILED);
        } catch (RuntimeException e) {
            return unknownPreCall(node, started);
        }
    }

    /**
     * 任务内同工具 + 生效幂等键的有效证据（STARTED / 算成功的 SUCCESS / UNKNOWN / UNKNOWN_PRE_CALL）。
     * FAILED / CANCELLED / SUCCESS 且 businessOk=false 不命中，允许重试。
     * <p>先看本节点，再 HGET {@code task:writes}，不再全图打开 space。</p>
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
            throw new BusinessException(RuntimeErrorCode.STATE_UNAVAILABLE);
        }
        WriteIndexEntry entry = JSON.str2obj(raw, WriteIndexEntry.class);
        if (Objects.nonNull(entry) && entry.live() && Strings.isNotBlank(entry.evidenceId())) {
            return Optional.of(new EvidenceHead(entry.evidenceId(), toolName, SideEffect.WRITE, entry.status(),
                entry.businessOk(), null, null, FailureKind.NONE, key, null, null, null, null, null, null, 0, ""));
        }
        return persistence.findLiveWrite(taskId, toolName, key);
    }

    /**
     * 工具调用前登记超时或状态未知时，将 Redis 标记为 UNKNOWN_PRE_CALL，并尽量把 MySQL 追成同一状态。
     *
     * @param node    当前节点
     * @param started 已写入的 STARTED 证据
     * @return 阻断预写结果
     */
    private PrewriteResult unknownPreCall(DefaultNodeScope node, EvidenceHead started) {
        EvidenceHead unknown = withStatus(started, EvidenceStatus.UNKNOWN_PRE_CALL, FailureKind.UNKNOWN, null);
        node.addEvidence(unknown);
        flushPlan.apply(node.turn(), false);
        try {
            persistence.updateCall(unknown, null);
        } catch (RuntimeException ignored) {
            // 保留 Redis；轮末 persistTurn 按 call_id 再追
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
        EvidenceHead settled = new EvidenceHead(current.evidenceId(), current.toolName(), current.sideEffect(),
            result.status(), result.businessOk(), result.empty(), result.extractCounts(), result.failureKind(),
            current.idempotencyKey(), current.argsHash(), current.argsBrief(), clipBrief(result.resultBrief()),
            result.errorCode(), clipEntities(entities), current.payloadId(), current.round(), current.traceId());
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
        EvidenceHead head = headOf(node, call, result.status(), result, result.failureKind(), result.errorCode(),
            result.resultBrief());
        persistPayload(node, head, result);
        try {
            String body = json(result.payload());
            boolean oversize = oversized(body);
            ToolCallRecordDto dto = startedDto(node, call, head);
            dto.setCallStatus(result.status().name());
            dto.setResponse(oversize ? null : body);
            dto.setResultSummary(head.resultBrief());
            dto.setErrorCode(result.errorCode());
            persistence.insertStartedCall(dto);
            if (oversize) {
                String taskId = node.turn().task().map(TaskMetadata::getTaskId).orElse(null);
                if (Strings.isNotBlank(taskId) && Strings.isNotBlank(body)) {
                    persistence.saveArtifact(head.evidenceId(), taskId, node.nodeId(), head.toolName(),
                        node.turn().traceId(), body);
                }
            }
        } catch (RuntimeException e) {
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
        ToolCallRecordVo row = persistence.findCall(head.evidenceId());
        EvidenceHead cancelled = withStatus(head, EvidenceStatus.CANCELLED, FailureKind.UNKNOWN, null);
        if (Objects.nonNull(row)) {
            persistence.updateCall(head.evidenceId(), EvidenceStatus.CANCELLED.name(), row.getResponse(),
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
        EvidenceHead next = new EvidenceHead(current.evidenceId(), current.toolName(), current.sideEffect(), status,
            businessOk, current.empty(), current.extractCounts(), kind, current.idempotencyKey(), current.argsHash(),
            current.argsBrief(), current.resultBrief(), current.errorCode(), current.entities(), current.payloadId(),
            current.round(), current.traceId());
        node.addEvidence(next);
        flushPlan.apply(node.turn(), false);
        ToolCallRecordVo row = persistence.findCall(current.evidenceId());
        persistence.updateCall(next, Objects.isNull(row) ? null : row.getResponse());
    }

    /**
     * 写工具原始请求 JSON，供 verify.args 绑定 {@code call.*}。
     *
     * @param evidenceId 证据 id
     * @return 请求体；库中无记录为 {@code null}
     */
    public @Nullable String requestJson(String evidenceId) {
        ToolCallRecordVo row = persistence.findCall(evidenceId);
        return Objects.isNull(row) ? null : row.getRequest();
    }

    /**
     * 按本次调用拼一条证据头。
     *
     * @param node        当前节点
     * @param call        工具调用
     * @param status      证据状态
     * @param result      工具结果；可空
     * @param failureKind 失败分类
     * @param errorCode   错误码；可空
     * @param resultBrief 结果摘要；可空
     * @return 新证据头
     */
    private EvidenceHead headOf(DefaultNodeScope node, ToolCall call, EvidenceStatus status,
                                @Nullable ToolResult result, FailureKind failureKind, @Nullable String errorCode,
                                @Nullable String resultBrief) {
        String evidenceId = RuntimeUtils.uuid();
        String argsHash = RuntimeUtils.argsHash(call.args());
        String argsBrief = ArgsBrief.of(call.args(), call.contract(),
            properties.getReact().getBriefMaxChars());
        return new EvidenceHead(evidenceId, call.toolName(), call.sideEffect(), status,
            Objects.isNull(result) ? null : result.businessOk(), Objects.isNull(result) ? null : result.empty(),
            Objects.isNull(result) ? null : result.extractCounts(), failureKind, liveKey(call), argsHash, argsBrief,
            clipBrief(resultBrief), errorCode,
            clipEntities(Objects.isNull(result) ? null : result.entities()), null, node.rounds(),
            node.turn().traceId());
    }

    /**
     * 按 React 摘要上限裁剪结果简述。
     *
     * @param text 原文；可空
     * @return 裁剪后文本；空白入参原样返回
     */
    private @Nullable String clipBrief(@Nullable String text) {
        return clipChars(text, properties.getReact().getBriefMaxChars());
    }

    /**
     * 按实体字段上限裁剪证据实体 JSON。
     *
     * @param text 原文；可空
     * @return 裁剪后文本；空白入参原样返回
     */
    private @Nullable String clipEntities(@Nullable String text) {
        return clipChars(text, properties.getState().getEvidenceEntitiesMaxChars());
    }

    /**
     * 按字符数截断文本。
     *
     * @param text     原文；可空
     * @param maxChars 最大字符数；小于等于 0 不截
     * @return 截断结果；空白入参原样返回
     */
    private static @Nullable String clipChars(@Nullable String text, int maxChars) {
        if (Strings.isBlank(text) || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    /**
     * 复制证据头并改状态、失败分类与错误码。
     *
     * @param head   原证据
     * @param status 新状态
     * @param kind   失败分类
     * @param error  错误码；可空
     * @return 新证据头
     */
    @SuppressWarnings("SameParameterValue")
    private static EvidenceHead withStatus(EvidenceHead head, EvidenceStatus status, FailureKind kind, @Nullable String error) {
        return new EvidenceHead(head.evidenceId(), head.toolName(), head.sideEffect(), status, head.businessOk(),
            head.empty(), head.extractCounts(), kind, head.idempotencyKey(), head.argsHash(), head.argsBrief(),
            head.resultBrief(), error, head.entities(), head.payloadId(), head.round(), head.traceId());
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
     * 复制证据头并改报文 id。
     *
     * @param head      原证据
     * @param payloadId 报文 id；可空表示内联
     * @return 新证据头
     */
    private static EvidenceHead withPayloadId(EvidenceHead head, @Nullable String payloadId) {
        return new EvidenceHead(head.evidenceId(), head.toolName(), head.sideEffect(), head.status(),
            head.businessOk(), head.empty(), head.extractCounts(), head.failureKind(), head.idempotencyKey(),
            head.argsHash(), head.argsBrief(), head.resultBrief(), head.errorCode(), head.entities(), payloadId,
            head.round(), head.traceId());
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
        EvidenceHead stored = oversized(body) ? withPayloadId(head, head.evidenceId()) : head;
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
            persistence.updateCall(head, body);
            return;
        }
        persistence.updateCall(head, null);
        String taskId = node.turn().task().map(TaskMetadata::getTaskId).orElse(null);
        if (Strings.isBlank(taskId) || Strings.isBlank(body)) {
            return;
        }
        persistence.saveArtifact(head.evidenceId(), taskId, node.nodeId(), head.toolName(),
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
