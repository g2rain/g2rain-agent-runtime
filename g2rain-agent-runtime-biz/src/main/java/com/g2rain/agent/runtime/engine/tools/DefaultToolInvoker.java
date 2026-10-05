package com.g2rain.agent.runtime.engine.tools;


import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.StringNode;

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
     * 按执行顺序排列的调用前后拦截器；构造时完成排序，调用期间只读。
     */
    private final List<ToolInterceptor> interceptors;

    /**
     * 工具开始时的过程提示。
     */
    private final ToolEvents toolEvents;

    /**
     * 创建工具调用编排器。
     *
     * <p>拦截器按 Spring 的 order 元数据排序，并在调用结束时反向执行 after 逻辑，
     * 使外层拦截器能够包住内层拦截器和实际工具调用。</p>
     *
     * @param interceptors 调用前后拦截器；允许为空集合
     * @param toolEvents   工具开始提示发布器
     */
    public DefaultToolInvoker(List<ToolInterceptor> interceptors, ToolEvents toolEvents) {
        // 构造期按 order 排成固定序列，调用期不再重排，保证 before 正序与 after 逆序成对
        this.interceptors = interceptors.stream().sorted(AnnotationAwareOrderComparator.INSTANCE).toList();
        this.toolEvents = toolEvents;
    }

    /**
     * 执行一次工具调用并返回标准化观测结果。
     *
     * <p>调用前拦截器按正序执行，首个阻断结果会立即返回；只有全部通过时才会发布
     * 过程提示并调用工具。工具返回后，after 拦截器按逆序处理结果。</p>
     *
     * @param request 工具名称、参数、上下文和契约信息
     * @return 工具成功或失败的统一观测结果
     */
    @Override
    public Observation invoke(ToolInvokeRequest request) {
        // 正序 before：任一拦截器给出观测即短路，工具不会被调用，也不会发提示
        for (ToolInterceptor interceptor : interceptors) {
            Optional<Observation> blocked = interceptor.before(request);
            if (blocked.isPresent()) {
                return blocked.get();
            }
        }

        toolEvents.started(request);
        // 实际回调只发生在全部 before 通过之后；异常在 doInvoke 内收成 Observation
        Observation obs = doInvoke(request);

        // 逆序 after：外层拦截器后处理内层结果，可改写观测但不能撤销已发生的工具副作用
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            obs = interceptors.get(i).after(request, obs);
        }

        return obs;
    }

    /**
     * 调用实际工具并把运行时异常转换为业务观测。
     *
     * <p>这是外部工具回调的唯一调用点。上下文先转换为 Spring AI 所需类型，
     * 工具返回文本则交给 {@link #successOf(String)} 解析；任何运行时异常都交给
     * {@link #failureOf(ToolInvokeRequest, RuntimeException)} 分类。</p>
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
            // 不把原始异常抛出编排层，按原因收成失败观测
            return failureOf(request, ex);
        }
    }

    /**
     * 把工具返回文本转换为成功观测。
     *
     * <p>优先把返回值解析为 JSON；部分工具只返回普通文本时，文本会被包装成 JSON
     * 字符串节点，仍然按成功处理，不会因为返回格式不是 JSON 而误判工具失败。</p>
     *
     * @param result 工具返回文本；允许为空
     * @return 包含文本摘要和结构化数据的成功观测
     */
    private Observation successOf(String result) {
        String text = Objects.toString(result, "");
        try {
            return Observation.success(text, JSON.str2node(text));
        } catch (RuntimeException _) {
            // 工具已执行成功；非 JSON 一律按文本节点记 SUCCESS
            StringNode data = JsonNodeFactory.instance.stringNode(text);
            return Observation.success(text, data);
        }
    }

    /**
     * 将工具异常按原因转换为稳定的运行时错误码。
     *
     * <p>先沿异常链识别超时、连接失败和 MCP RPC 错误，再处理受信任的业务错误码、
     * IO 错误和兜底工具错误。分类只影响观测结果，不在此处重试工具调用。</p>
     *
     * @param request 当前工具请求，用于判断内置工具和 MCP 工具错误是否可透传
     * @param ex      工具调用抛出的异常
     * @return 携带用户可读消息和稳定错误码的失败观测
     */
    private Observation failureOf(ToolInvokeRequest request, RuntimeException ex) {
        Throwable cause = ex;
        while (Objects.nonNull(cause)) {
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) {
                return Observation.failure(messageOf(ex), RuntimeErrorCode.TOOL_TIMEOUT.code());
            }

            if (cause instanceof ConnectException) {
                String code = RuntimeErrorCode.TOOL_UNREACHABLE.code();
                return Observation.failure(messageOf(ex), code);
            }

            if (cause instanceof McpError mcpError) {
                return mcpFailure(mcpError, ex);
            }

            // 先继续向下找更具体的原因，最后再落 IO
            cause = cause.getCause();
        }

        if (ex instanceof BusinessException business) {
            String code = business.getErrorCode();
            if (listedRuntimeCode(code)
                && (ToolCallbacks.isBuiltin(request.toolCallback()) || isMcpToolError(code))) {
                return Observation.failure(messageOf(ex), code);
            }
            return Observation.failure(messageOf(ex), RuntimeErrorCode.TOOL_ERROR.code());
        }

        cause = ex;
        while (Objects.nonNull(cause)) {
            if (cause instanceof IOException) {
                return Observation.failure(messageOf(ex), RuntimeErrorCode.TOOL_IO_ERROR.code());
            }

            cause = cause.getCause();
        }

        return Observation.failure(messageOf(ex), RuntimeErrorCode.TOOL_ERROR.code());
    }

    /**
     * 判断错误码是否属于 MCP 工具错误。
     *
     * @param code 待判断的错误码
     * @return 是否为 MCP_TOOL_ERROR 的 code 或枚举名
     */
    private static boolean isMcpToolError(String code) {
        return Objects.equals(code, RuntimeErrorCode.MCP_TOOL_ERROR.code())
            || Objects.equals(code, RuntimeErrorCode.MCP_TOOL_ERROR.name());
    }

    /**
     * 判断字符串是否能匹配运行时错误码的 code 或枚举名。
     *
     * @param code 待判断的错误码
     * @return 是否为已登记的运行时错误码
     */
    private static boolean listedRuntimeCode(@Nullable String code) {
        if (Strings.isBlank(code)) {
            return false;
        }

        for (RuntimeErrorCode item : RuntimeErrorCode.values()) {
            if (Objects.equals(code, item.code()) || Objects.equals(code, item.name())) {
                return true;
            }
        }

        return false;
    }

    /**
     * 将 MCP JSON-RPC 错误映射为 Runtime 错误码。
     *
     * @param mcpError MCP 返回的 RPC 错误
     * @param root     原始异常，用于保留用户可读消息
     * @return MCP 参数错误或通用 RPC 错误观测
     */
    private static Observation mcpFailure(McpError mcpError, RuntimeException root) {
        var rpc = mcpError.getJsonRpcError();
        if (Objects.nonNull(rpc) && rpc.code() == McpSchema.ErrorCodes.INVALID_PARAMS) {
            return Observation.failure(messageOf(root), RuntimeErrorCode.MCP_INVALID_PARAMS.code());
        }

        return Observation.failure(messageOf(root), RuntimeErrorCode.MCP_RPC_ERROR.code());
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
