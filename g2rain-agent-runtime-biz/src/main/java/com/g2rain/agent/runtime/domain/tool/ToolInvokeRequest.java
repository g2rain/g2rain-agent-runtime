package com.g2rain.agent.runtime.domain.tool;


import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一次待执行工具调用的可变入参包。
 * <p>
 * 绑定执行上下文、工具名与参数 JSON，并在构造时从回调列表里按名匹配 {@link ToolCallback}。
 * 允许在真正调用前用 {@link #replaceArguments(String)} 改写参数（如契约注入后）。
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class ToolInvokeRequest {

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
     * 当前工具名和参数生成的稳定指纹；参数被替换后必须同步更新。
     */
    private String fingerprint;

    /**
     * 当前工具使用的契约；可为空，例如内置控制工具路径。
     */
    private final @Nullable ToolSpec contract;

    /**
     * 工具参数 JSON 字符串；可读时空白会按 "{}" 返回。
     */
    private String arguments;

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
     * <p>构造时立即生成初始指纹，并从当前技能契约解析工具配置；后续通过
     * {@link #replaceArguments(String)} 改写参数时会重新计算指纹。</p>
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
        this.arguments = args;
        this.fingerprint = ToolExecutionContext.fingerprintOf(toolName(), arguments());
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
     * 当前调用的稳定参数指纹
     *
     * @return 工具名与规范化参数生成的指纹
     */
    public String fingerprint() {
        return this.fingerprint;
    }

    /**
     * 参数 JSON；空白时返回 "{}"。
     *
     * @return 参数字符串
     */
    public String arguments() {
        return Strings.defaultIfBlank(this.arguments, "{}");
    }

    /**
     * 覆盖参数 JSON 并重新计算调用指纹。
     *
     * <p>该方法只负责更新内存中的请求，不会自动发起第二次工具调用；调用方仍需重新执行
     * 预写和保护校验。</p>
     *
     * @param arguments 新的参数 JSON
     */
    public void replaceArguments(String arguments) {
        this.arguments = arguments;
        this.fingerprint = ToolExecutionContext.fingerprintOf(toolName(), arguments());
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
