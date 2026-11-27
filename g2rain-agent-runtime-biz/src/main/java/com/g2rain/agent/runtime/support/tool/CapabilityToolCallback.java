package com.g2rain.agent.runtime.support.tool;

import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.ToolExecutionContexts;
import com.g2rain.agent.runtime.engine.tools.guard.EvidenceReader;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.i18n.RuntimeMessageRegistry;
import com.g2rain.agent.runtime.spi.Capability;
import com.g2rain.agent.runtime.spi.CapabilityCall;
import com.g2rain.agent.runtime.spi.CapabilityResult;
import com.g2rain.agent.runtime.spi.CapabilitySpec;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 把 {@link Capability} 适配为 Spring AI {@link ToolCallback}。
 *
 * <p>工具定义取自 {@link CapabilitySpec}；成功返回结果文本，失败抛出 {@link CapabilityFailure}。</p>
 *
 * @author alpha
 * @since 2026/11/24
 */
@Slf4j
public final class CapabilityToolCallback implements ToolCallback {

    /**
     * JSON 编解码：解析入参与序列化 Schema。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 能力工具多语言文案编码前缀；完整编码为 {@code capability.{name}}。
     */
    public static final String MESSAGE_CODE_PREFIX = "capability.";

    /**
     * 能力实现。
     */
    private final Capability capability;

    /**
     * 证据读取器。
     */
    private final EvidenceReader evidenceReader;

    /**
     * 状态门面。
     */
    private final RuntimeStateFacade stateFacade;

    /**
     * 启动时算好的工具定义。
     */
    private final ToolDefinition definition;

    /**
     * @param capability     能力实现
     * @param evidenceReader 证据读取器
     * @param stateFacade    状态门面
     */
    public CapabilityToolCallback(Capability capability, EvidenceReader evidenceReader,
                                  RuntimeStateFacade stateFacade) {
        this.capability = Objects.requireNonNull(capability);
        this.evidenceReader = Objects.requireNonNull(evidenceReader);
        this.stateFacade = Objects.requireNonNull(stateFacade);
        CapabilitySpec spec = capability.spec();
        this.definition = ToolDefinition.builder()
            .name(spec.name())
            .description(spec.description())
            .inputSchema(JSON.obj2str(spec.inputSchema()))
            .build();
    }

    /**
     * @return 能力说明
     */
    public CapabilitySpec spec() {
        return capability.spec();
    }

    /**
     * 按请求语言取展示名：查 {@link RuntimeMessageRegistry}，编码为前缀加工具名；没有文案时回落 {@link CapabilitySpec#label()}。
     *
     * @param locale 请求语言；可空
     * @return 展示名
     */
    public String label(@Nullable String locale) {
        CapabilitySpec spec = capability.spec();
        return RuntimeMessageRegistry.resolve(MESSAGE_CODE_PREFIX + spec.name(), locale, spec.label());
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        long start = System.nanoTime();
        boolean ok = false;
        int chars = 0;
        try {
            JsonNode arguments = parseArguments(toolInput);
            if (Objects.isNull(toolContext)) {
                throw new IllegalStateException("Missing ToolContext");
            }

            ToolExecutionContext execution = ToolExecutionContexts.requireSpring(toolContext);
            CapabilityCall call = new CapabilityCall(arguments,
                new ToolCapabilityContext(execution, evidenceReader, stateFacade));

            CapabilityResult result;
            try {
                result = capability.call(call);
            } catch (CapabilityFailure ex) {
                throw ex;
            } catch (RuntimeException ex) {
                // 插件未预期异常：记 ERROR，转为能力失败
                log.error("capability execution failed tool={}", definition.name(), ex);
                throw new CapabilityFailure(ModelHint.CAPABILITY_FAILED.text(),
                    ModelHint.NEXT_CAPABILITY_FALLBACK.text(), ex);
            }

            if (!result.ok()) {
                String next = Strings.isBlank(result.next())
                    ? ModelHint.NEXT_CAPABILITY_FALLBACK.text()
                    : result.next();
                throw new CapabilityFailure(result.content(), next, result.unavailable());
            }

            String content = Objects.toString(result.content(), "");
            ok = true;
            chars = content.length();
            if (capability.spec().external()) {
                return ExternalContent.wrap(definition.name(), content);
            }

            return content;
        } finally {
            Trace.at(TraceStage.TOOL, "capability")
                .kv("tool", definition.name())
                .kv("durationMs", (System.nanoTime() - start) / 1_000_000L)
                .kv("chars", chars)
                .kv("ok", ok)
                .info();
        }
    }

    /**
     * 解析入参 JSON；失败抛出能力失败。
     *
     * @param toolInput 入参字符串
     * @return JSON 节点
     */
    private static JsonNode parseArguments(String toolInput) {
        String raw = Strings.isBlank(toolInput) ? "{}" : toolInput;
        try {
            return JSON.str2node(raw);
        } catch (RuntimeException ex) {
            String message = Objects.requireNonNullElse(ex.getMessage(), ex.getClass().getSimpleName());
            throw new CapabilityFailure(ModelHint.ARG_JSON_INVALID.text("detail", message),
                ModelHint.NEXT_FIX_ARGS.text(), ex);
        }
    }
}
