package com.g2rain.agent.runtime.engine.tools;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.hydrate.OutputSpec;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.Outcome;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.dto.ToolBlockRecordDto;
import com.g2rain.agent.runtime.engine.state.repo.BlockRepository;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.ArgsBrief;
import com.g2rain.agent.runtime.support.JsonClip;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.agent.runtime.support.tool.CapabilityFailure;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceEvent;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContextHolder;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * 工具调用编排器。
 *
 * <p>调用顺序固定为：按顺序执行拦截器的 {@code before} 检查，调用实际工具，
 * 再按相反顺序执行 {@code after} 处理。任何拦截器返回阻断结果时都不会调用工具；
 * 工具异常则转换为统一的 {@link Observation}，避免异常类型直接泄漏到上层。</p>
 *
 * <p>本类不保存单次调用状态。调用标识、耗时和结果事件只在当前调用期间生成，
 * 持久化或证据状态由拦截器及状态门面负责。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
public class DefaultToolInvoker implements ToolInvoker {

    /**
     * 用于把工具文本结果转换为结构化观测载荷的 JSON 编解码器。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 轨迹中工具入参摘要的字符数上限。
     */
    private static final int ARGS_BRIEF_MAX_CHARS = 200;

    /**
     * {@code builtin_output_set} 的产出键参数名。
     */
    private static final String OUTPUT_KEY_ARG = "key";

    /**
     * {@code builtin_output_set} 的产出值参数名。
     */
    private static final String OUTPUT_VALUE_ARG = "value";

    /**
     * 响应信封中对模型无意义的字段。
     */
    private static final List<String> ENVELOPE_NOISE = List.of("requestId", "requestTime");

    /**
     * 按执行顺序排列的调用前后拦截器；构造时完成排序，调用期间只读。
     */
    private final List<ToolInterceptor> interceptors;

    /**
     * 工具开始时的过程提示。
     */
    private final ToolEvents toolEvents;

    /**
     * 运行时配置：成功结果给模型的字符上限。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 业务工具拦截记录仓储。
     */
    private final BlockRepository blockRepository;

    /**
     * 创建工具调用编排器。
     *
     * <p>拦截器按 Spring 的 order 元数据排序，并在调用结束时反向执行 after 逻辑，
     * 使外层拦截器能够包住内层拦截器和实际工具调用。</p>
     *
     * @param interceptors      调用前后拦截器；允许为空集合
     * @param toolEvents        工具开始提示发布器
     * @param runtimeProperties 运行时配置
     * @param blockRepository   拦截记录仓储
     */
    public DefaultToolInvoker(List<ToolInterceptor> interceptors, ToolEvents toolEvents,
                              RuntimeProperties runtimeProperties, BlockRepository blockRepository) {
        // 构造期按 order 排成固定序列，调用期不再重排，保证 before 正序与 after 逆序成对
        this.interceptors = interceptors.stream().sorted(AnnotationAwareOrderComparator.INSTANCE).toList();
        this.toolEvents = toolEvents;
        this.runtimeProperties = runtimeProperties;
        this.blockRepository = blockRepository;
    }

    /**
     * 执行一次工具调用并返回标准化观测结果。
     *
     * <p>节点尚无完成目标（或自动认领的目标尚未写成功）时，先把本次写调用记为目标。调用前拦截器按正序执行，首个阻断结果会立即返回；
     * 只有全部通过时才会发布过程提示并调用工具。工具返回后，after 拦截器按逆序处理结果。</p>
     *
     * @param request 工具名称、参数、上下文和契约信息
     * @return 工具成功或失败的统一观测结果
     */
    @Override
    public Observation invoke(ToolInvokeRequest request) {
        long start = System.nanoTime();
        // 节点尚无完成目标、或自动认领的目标尚未写成功时，本次写调用（无论后续是否被拦下）成为目标
        if (request.context().claimTarget(request.toolName())) {
            Trace.at(TraceStage.NODE, "target_claimed")
                .kv("nodeId", request.context().getNodeId())
                .kv("target", request.toolName())
                .info();
        }

        // 正序 before：任一拦截器给出观测即短路，工具不会被调用，也不会发提示
        for (ToolInterceptor interceptor : interceptors) {
            Optional<Observation> blocked = interceptor.before(request);
            if (blocked.isPresent()) {
                Observation obs = blocked.get();
                recordBlock(request, obs, interceptor);
                trace(request, obs, interceptor, start);
                showRejected(request, obs);
                return obs;
            }
        }

        String stepId = toolEvents.started(request);
        // 实际回调只发生在全部 before 通过之后；异常在 doInvoke 内收成 Observation
        long callStart = System.nanoTime();
        Observation obs = doInvoke(request);
        request.durationMs((System.nanoTime() - callStart) / 1_000_000L);

        // 逆序 after：外层拦截器后处理内层结果，可改写观测但不能撤销已发生的工具副作用
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            obs = interceptors.get(i).after(request, obs);
        }

        trace(request, obs, null, start);
        toolEvents.finished(stepId, request, obs, argsBrief(request));
        return obs.withNotices(request.notices().stream()
            .map(notice -> ModelHint.GUARD_NOTICE.text("message", notice.message()))
            .toList());
    }

    /**
     * 业务调用在调用前被拒（如附加检查不通过）时，也在执行过程中显示一步失败，让用户看到被拦下的调用；
     * 推迟（等待确认等）与需先补查（{@code GUARD_LOOKUP}，随后的查询步骤与重新调用即可表达）不显示；内置工具不显示。
     *
     * @param request 工具调用请求
     * @param obs     短路观测
     */
    private void showRejected(ToolInvokeRequest request, Observation obs) {
        if (obs.outcome() != Outcome.REJECTED || obs.errorCode() == RuntimeErrorCode.GUARD_LOOKUP
            || ToolCallbacks.isBuiltin(request.toolCallback())) {
            return;
        }

        String stepId = toolEvents.started(request);
        toolEvents.finished(stepId, request, obs, argsBrief(request));
    }

    /**
     * 业务工具被 before 短路时写入拦截记录；内置工具跳过。写入失败只记 WARN，不影响拦截结论。
     *
     * @param request     工具调用请求
     * @param obs         短路观测
     * @param interceptor 拦下调用的拦截器
     */
    private void recordBlock(ToolInvokeRequest request, Observation obs, ToolInterceptor interceptor) {
        RuntimeErrorCode errorCode = obs.errorCode();
        if (ToolCallbacks.isBuiltin(request.toolCallback()) || Objects.isNull(errorCode)) {
            return;
        }

        String blockedBy = ClassUtils.getUserClass(interceptor).getSimpleName();
        ToolExecutionContext context = request.context();
        String skillKey = context.getSkillKey();
        if (Strings.isBlank(skillKey)) {
            log.warn("skip tool block record: blank skillKey tool={} callId={} blockedBy={}",
                request.toolName(), request.callId(), blockedBy);
            return;
        }

        try {
            Skill skill = context.getSkill();
            ToolBlockRecordDto dto = new ToolBlockRecordDto();
            dto.setOrganId(PrincipalContextHolder.getOrganId());
            dto.setTaskId(context.getTaskId());
            dto.setNodeId(context.getNodeId());
            dto.setRound(context.scope().rounds());
            dto.setCallId(request.callId());
            dto.setSkillKey(skillKey);
            dto.setSkillPin(Objects.isNull(skill) ? null : skill.pin());
            dto.setToolName(request.toolName());
            dto.setBlockedBy(blockedBy);
            dto.setErrorCode(errorCode.code());
            dto.setReasonCode(obs.reasonCode());
            dto.setMessage(obs.text());
            dto.setNextStep(obs.next());
            dto.setArgs(argsBrief(request));
            dto.setTraceId(context.scope().turn().traceId());
            blockRepository.insert(dto);
        } catch (RuntimeException ex) {
            log.warn("failed to persist tool block record tool={} callId={} blockedBy={}",
                request.toolName(), request.callId(), blockedBy, ex);
        }
    }

    /**
     * 输出 {@code [Trace][TOOL] invoked}：结论类型、错误码、拦下调用的拦截器，参数只输出脱敏摘要；
     * 工具实际执行失败（{@link Outcome#FAILED}）时以 WARN 输出。
     *
     * @param request    工具调用请求
     * @param obs        最终观测
     * @param blocker    拦下调用的拦截器；实际调用了工具时为 {@code null}
     * @param startNanos 起点（{@link System#nanoTime()}）
     */
    private static void trace(ToolInvokeRequest request, Observation obs, @Nullable ToolInterceptor blocker, long startNanos) {
        if (!Trace.enabled(TraceStage.TOOL)) {
            return;
        }

        TraceEvent event = Trace.at(TraceStage.TOOL, "invoked")
            .kv("tool", request.toolName())
            .kv("outcome", obs.outcome())
            .kv("errorCode", obs.errorCode())
            .kv("blockedBy", Objects.isNull(blocker) ? null : ClassUtils.getUserClass(blocker).getSimpleName())
            .kv("durationMs", (System.nanoTime() - startNanos) / 1_000_000L)
            .kv("args", argsBrief(request));
        if (obs.outcome() == Outcome.FAILED) {
            event.warn();
            return;
        }

        event.info();
    }

    /**
     * 工具入参的脱敏摘要；入参不是 JSON 对象时为 {@code {}}。
     *
     * @param request 工具调用请求
     * @return 脱敏摘要
     */
    private static String argsBrief(ToolInvokeRequest request) {
        if (sensitiveOutput(request)) {
            ObjectNode masked = request.args().deepCopy();
            masked.put(OUTPUT_VALUE_ARG, ArgsBrief.MASK);
            return ArgsBrief.of(masked, request.contract(), ARGS_BRIEF_MAX_CHARS);
        }

        return ArgsBrief.of(request.args(), request.contract(), ARGS_BRIEF_MAX_CHARS);
    }

    /**
     * 是否为写入敏感产出的 {@code builtin_output_set}：契约 {@code outputs.<key>.sensitive} 为 {@code true}。
     *
     * @param request 工具调用请求
     * @return 写入敏感产出时为 {@code true}
     */
    private static boolean sensitiveOutput(ToolInvokeRequest request) {
        if (!ToolCallbacks.is(request.toolCallback(), BuiltinToolAction.OUTPUT)) {
            return false;
        }

        SkillContract contract = request.context().contract();
        if (Objects.isNull(contract)) {
            return false;
        }

        OutputSpec spec = contract.getOutputs().get(request.args().path(OUTPUT_KEY_ARG).asString().trim());
        return Objects.nonNull(spec) && spec.isSensitive();
    }

    /**
     * 调用实际工具并把运行时异常转换为业务观测。
     *
     * <p>这是外部工具回调的唯一调用点。上下文先转换为 Spring AI 所需类型，
     * 工具返回文本则交给 {@link #successOf(String)} 解析；任何运行时异常都交给
     * {@link #observationOf(ToolInvokeRequest, RuntimeException)} 分类。</p>
     *
     * @param request 当前工具调用请求
     * @return 成功观测或包含统一错误码的失败观测
     */
    private Observation doInvoke(ToolInvokeRequest request) {
        try {
            ToolCallback callback = request.toolCallback();
            var ctx = ToolExecutionContexts.toSpring(request.context());
            // 唯一外部回调点：上下文已转成 Spring AI 形态，结果一律走 successOf
            String result = callback.call(request.arguments(), ctx);
            return successOf(result);
        } catch (RuntimeException ex) {
            logFailure(request, ex);
            // 不把原始异常抛出编排层，按原因收成失败观测
            return observationOf(request, ex);
        }
    }

    /**
     * 按异常性质记录调用失败。
     *
     * <ul>
     *   <li>内置工具的规则拒绝（{@link ToolRejectedException}）属正常控流，工具轨迹已记 REJECTED 与错误码，不再记录；</li>
     *   <li>业务失败（{@link BusinessException}，且不是超时或连接失败）记 WARN，不带堆栈；</li>
     *   <li>其余异常记 ERROR 并带堆栈。</li>
     * </ul>
     *
     * @param request 当前工具调用请求
     * @param ex      工具调用抛出的异常
     */
    private static void logFailure(ToolInvokeRequest request, RuntimeException ex) {
        // 能力失败已由回调侧处理（插件异常已记 ERROR）；此处不再重复打堆栈
        if (ex instanceof CapabilityFailure) {
            return;
        }

        BusinessException business = businessOf(ex);
        if (business instanceof ToolRejectedException && ToolCallbacks.isBuiltin(request.toolCallback())) {
            return;
        }

        if (Objects.nonNull(business) && !transportFailure(ex)) {
            log.warn("tool business failure tool={} errorCode={} message={}",
                request.toolName(), business.getErrorCode(), business.getMessage());
            return;
        }

        log.error("failed to doInvoke tool={}", request.toolName(), ex);
    }

    /**
     * 异常链上是否有超时或连接失败。
     *
     * @param ex 工具调用抛出的异常
     * @return 超时或连接失败时为 {@code true}
     */
    private static boolean transportFailure(Throwable ex) {
        Throwable cause = ex;
        while (Objects.nonNull(cause)) {
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException
                || cause instanceof ConnectException) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }

    /**
     * 给模型看的结果：去掉响应信封噪声，超出上限时裁剪并注明已省略；未改动时沿用工具原文。
     *
     * @param text 工具返回原文
     * @param data 解析后的 JSON；非 JSON 返回为文本节点
     * @return 给模型的文本
     */
    private String visibleText(String text, JsonNode data) {
        int max = runtimeProperties.getReact().getObservationMaxChars();
        if (data.isString()) {
            return text.length() <= max ? text : text.substring(0, max) + ModelHint.OBSERVATION_CLIPPED.text();
        }

        JsonNode visible = withoutEnvelope(data);
        String json = visible == data ? text : JSON.obj2str(visible);
        return json.length() <= max ? json : JsonClip.of(visible, max) + ModelHint.OBSERVATION_CLIPPED.text();
    }

    /**
     * 去掉响应信封中对模型无意义的字段；不是对象或没有噪声字段时原样返回。
     *
     * @param data 解析后的 JSON
     * @return 去噪后的副本，或原节点
     */
    private static JsonNode withoutEnvelope(JsonNode data) {
        if (!(data instanceof ObjectNode object) || ENVELOPE_NOISE.stream().noneMatch(object::has)) {
            return data;
        }

        ObjectNode trimmed = object.deepCopy();
        ENVELOPE_NOISE.forEach(trimmed::remove);
        return trimmed;
    }

    /**
     * 把工具返回文本转换为成功观测。
     *
     * <p>优先把返回值解析为 JSON；部分工具只返回普通文本时，文本会被包装成 JSON
     * 字符串节点，仍然按成功处理，不会因为返回格式不是 JSON 而误判工具失败。
     * 给模型的文本去掉响应信封里的请求标识与时间，超长时保结构裁剪；结构化数据保持原样。</p>
     *
     * @param result 工具返回文本；允许为空
     * @return 包含文本摘要和结构化数据的成功观测
     */
    private Observation successOf(String result) {
        String text = Objects.toString(result, "");
        if (Strings.isBlank(text)) {
            return Observation.success(text, null);
        }

        JsonNode data;
        try {
            data = JSON.str2node(text);
        } catch (RuntimeException _) {
            // 工具已执行成功；非 JSON 一律按文本节点记 SUCCESS
            data = JsonNodeFactory.instance.stringNode(text);
        }

        // 证据保留完整报文，只裁剪给模型看的文本
        return Observation.success(visibleText(text, data), data);
    }

    /**
     * 将工具异常按原因转换为观测。
     *
     * <p>先沿异常链识别超时、连接失败和 MCP RPC 错误（执行失败）；再看业务异常：内置工具抛出的
     * {@link ToolRejectedException} 视为调用不合规（拒绝，带其下一步），MCP 工具业务错误与其它业务异常视为执行失败；
     * 最后是 IO 错误和兜底工具错误。执行失败的下一步取自 {@link ToolErrors}。分类只影响观测结果，不在此处重试工具调用。</p>
     *
     * @param request 当前工具请求，用于判断是否内置工具
     * @param ex      工具调用抛出的异常
     * @return 执行失败或拒绝观测
     */
    private Observation observationOf(ToolInvokeRequest request, RuntimeException ex) {
        // 能力工具失败：原因与下一步原样进入失败观测
        if (ex instanceof CapabilityFailure failure) {
            return Observation.failed(RuntimeErrorCode.TOOL_ERROR, failure.reason(), failure.next());
        }

        Throwable cause = ex;
        while (Objects.nonNull(cause)) {
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) {
                return ToolErrors.TOOL_TIMEOUT.observe();
            }

            if (cause instanceof ConnectException) {
                return ToolErrors.TOOL_UNREACHABLE.observe();
            }

            if (cause instanceof McpError mcpError) {
                return mcpFailure(mcpError, ex);
            }

            // 先继续向下找更具体的原因，最后再落 IO
            cause = cause.getCause();
        }

        BusinessException business = businessOf(ex);
        if (business instanceof ToolRejectedException r && ToolCallbacks.isBuiltin(request.toolCallback())) {
            return Observation.rejected(r.error(), messageOf(r), r.next());
        }

        if (Objects.nonNull(business)) {
            if (Objects.equals(business.getErrorCode(), RuntimeErrorCode.MCP_TOOL_ERROR.code())) {
                return ToolErrors.MCP_TOOL_ERROR.observe(
                    ModelHint.MCP_TOOL_FAILED.text("message", RuntimeUtils.fenced(messageOf(business))));
            }

            return ToolErrors.TOOL_ERROR.observe(messageOf(business));
        }

        cause = ex;
        while (Objects.nonNull(cause)) {
            if (cause instanceof IOException) {
                return ToolErrors.TOOL_IO_ERROR.observe();
            }

            cause = cause.getCause();
        }

        // 传输层与未分类异常的原文只进日志（logFailure），给模型归类后的说明
        return ToolErrors.TOOL_ERROR.observe();
    }

    /**
     * 沿异常链查找业务异常；工具框架可能把工具抛出的业务异常包装在内层。
     *
     * @param ex 工具调用抛出的异常
     * @return 最外层的业务异常；没有则为 {@code null}
     */
    private static @Nullable BusinessException businessOf(Throwable ex) {
        Throwable cause = ex;
        while (Objects.nonNull(cause)) {
            if (cause instanceof BusinessException business) {
                return business;
            }

            cause = cause.getCause();
        }

        return null;
    }

    /**
     * 将 MCP JSON-RPC 错误映射为执行失败观测。
     *
     * @param mcpError MCP 返回的 RPC 错误
     * @param root     原始异常，用于保留用户可读消息
     * @return MCP 参数错误或通用 RPC 错误观测
     */
    private static Observation mcpFailure(McpError mcpError, RuntimeException root) {
        var rpc = mcpError.getJsonRpcError();
        if (Objects.nonNull(rpc) && rpc.code() == McpSchema.ErrorCodes.INVALID_PARAMS) {
            return ToolErrors.MCP_INVALID_PARAMS.observe(messageOf(root));
        }

        return ToolErrors.MCP_RPC_ERROR.observe(messageOf(root));
    }

    /**
     * 获取异常消息；异常没有消息时退回异常类名。
     *
     * @param ex 异常对象
     * @return 非空错误描述
     */
    private static String messageOf(Throwable ex) {
        return Objects.toString(ex.getMessage(), ex.getClass().getSimpleName());
    }
}
