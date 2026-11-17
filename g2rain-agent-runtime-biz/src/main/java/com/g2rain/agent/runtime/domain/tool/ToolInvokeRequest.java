package com.g2rain.agent.runtime.domain.tool;


import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.spec.ToolSpec;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一次待执行工具调用的可变入参包。
 * <p>
 * 绑定执行上下文、工具名与参数，并在构造时从回调列表里按名匹配 {@link ToolCallback}。
 * 参数只解析一次：业务工具由参数规范拦截器解析后经 {@link #args(ObjectNode)} 存入，拦截器在
 * {@link #args()} 这棵树上原地改写；真正调用工具时才由 {@link #arguments()} 序列化一次。
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class ToolInvokeRequest {

    /**
     * 参数序列化与原文解析。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 空参数原文。
     */
    private static final String EMPTY_OBJECT = "{}";

    /**
     * 工具执行上下文（节点运行时）。
     */
    private final ToolExecutionContext context;

    /**
     * 匹配到的工具回调；回调列表中无同名工具时为 {@code null}。
     */
    private final ToolCallback toolCallback;

    /**
     * 工具的注册名称。
     */
    private final String toolName;

    /**
     * 按原文生成的指纹；参数树存在时改按树计算。
     */
    private final String rawFingerprint;

    /**
     * 当前工具使用的契约；可为空，例如内置控制工具路径。
     */
    private final @Nullable ToolSpec contract;

    /**
     * 模型给出的参数原文。
     */
    private final String rawArguments;

    /**
     * 参数树：解析一次后由各拦截器原地改写；尚未解析或原文不是 JSON 对象时为空。
     */
    private @Nullable ObjectNode args;

    /**
     * 写工具完成调用前登记的证据 id；读工具和未真正调用的短路路径为空。
     */
    private @Nullable String evidenceId;

    /**
     * 工具实际执行的耗时（毫秒）；被拦截器短路、未真正调用时为空。
     */
    private @Nullable Long durationMs;

    /**
     * ArgBinding 写入的参数来源（paramPath → Source）。
     */
    private final Map<String, Source> argSources = new LinkedHashMap<>();

    /**
     * 组装调用请求并绑定工具回调。
     *
     * <p>构造时按原文生成指纹，并从当前技能契约解析工具配置。</p>
     *
     * @param context  执行上下文
     * @param callback 可用工具回调
     * @param args     参数 JSON
     */
    public ToolInvokeRequest(ToolExecutionContext context, ToolCallback callback, String args) {
        this.context = context;
        this.toolCallback = callback;
        this.toolName = callback.getToolDefinition().name();
        this.contract = skillTool();
        this.rawArguments = args;
        this.rawFingerprint = ToolExecutionContext.fingerprintOf(toolName, args);
    }

    /**
     * 记录预写阶段创建的证据标识。
     *
     * <p>该标识用于把调用前登记与调用后结果关联起来；方法本身不创建证据，也不改变工具状态。</p>
     *
     * @param evidenceId 预写证据 id
     */
    public void evidenceId(String evidenceId) {
        this.evidenceId = evidenceId;
    }

    /**
     * @return 写工具证据 id；可空
     */
    public @Nullable String evidenceId() {
        return this.evidenceId;
    }

    /**
     * 记录工具实际执行的耗时。
     *
     * @param durationMs 耗时毫秒
     */
    public void durationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    /**
     * @return 工具实际执行的耗时（毫秒）；未真正调用为空
     */
    public @Nullable Long durationMs() {
        return this.durationMs;
    }

    /**
     * 记录一个参数路径的来源证明。
     *
     * <p>空白路径不会写入来源表；同一路径再次绑定时以后一次来源为准，便于参数改写后保持
     * 来源与最终参数同步。</p>
     *
     * @param paramPath 参数路径
     * @param source    来源证明
     */
    public void argSource(String paramPath, Source source) {
        if (Strings.isNotBlank(paramPath)) {
            this.argSources.put(paramPath, source);
        }
    }

    /**
     * @return 参数来源表
     */
    public Map<String, Source> argSources() {
        return this.argSources;
    }

    /**
     * 工具的注册名称。
     *
     * @return 工具的注册名称
     */
    public String toolName() {
        return this.toolName;
    }

    /**
     * 当前调用的稳定参数指纹：参数树存在时按树计算（反映拦截器的改写），否则用原文指纹。
     *
     * @return 工具名与规范化参数生成的指纹
     */
    public String fingerprint() {
        return Objects.nonNull(args) ? ToolExecutionContext.fingerprintOf(toolName, args) : rawFingerprint;
    }

    /**
     * 模型给出的参数原文；空白时返回 "{}"。供参数规范拦截器做唯一一次解析。
     *
     * @return 参数原文
     */
    public String rawArguments() {
        return Strings.defaultIfBlank(rawArguments, EMPTY_OBJECT);
    }

    /**
     * 存入解析并清理后的参数树，之后各拦截器在这棵树上原地改写。
     *
     * @param normalized 参数树
     */
    public void args(ObjectNode normalized) {
        this.args = normalized;
    }

    /**
     * 参数树：尚未存入时按原文解析一次并缓存；原文不是 JSON 对象时返回一个空对象（不缓存）。
     *
     * @return 参数树
     */
    public ObjectNode args() {
        if (Objects.nonNull(args)) {
            return args;
        }

        try {
            if (JSON.str2node(rawArguments()) instanceof ObjectNode parsed) {
                this.args = parsed;
                return parsed;
            }
        } catch (RuntimeException _) {
            // 原文不是合法 JSON：业务工具已由参数规范拦截器拒调，这里只给只读方一个空对象
        }

        return JsonNodeFactory.instance.objectNode();
    }

    /**
     * 实际调用工具时使用的参数 JSON：参数树存在时由树序列化，否则返回原文。
     *
     * @return 参数字符串
     */
    public String arguments() {
        return Objects.nonNull(args) ? JSON.obj2str(args) : rawArguments();
    }

    /**
     * 匹配到的工具回调；未匹配则为 {@code null}。
     *
     * @return 工具回调；可空
     */
    public ToolCallback toolCallback() {
        return this.toolCallback;
    }

    /**
     * 执行上下文。
     *
     * @return 工具执行上下文
     */
    public ToolExecutionContext context() {
        return this.context;
    }

    /**
     * 当前工具契约；没有契约时返回空值。
     *
     * @return 工具契约
     */
    public @Nullable ToolSpec contract() {
        return this.contract;
    }

    /**
     * 从当前技能契约按工具名取得工具说明。
     *
     * @return 当前 Skill 中同名工具的契约；没有 Skill 或未配置时为 {@code null}
     */
    private @Nullable ToolSpec skillTool() {
        SkillContract skill = context().contract();
        // 内置控制工具没有 Skill 契约，绑定时保持 contract 为空
        if (Objects.isNull(skill)) {
            return null;
        }

        return skill.tool(toolName());
    }
}
