package com.g2rain.agent.runtime.engine.tools.builtin;



import lombok.extern.slf4j.Slf4j;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.SourceType;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.state.OutputContractValidator;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.ToolExecutionContexts;
import com.g2rain.agent.runtime.engine.tools.ToolRejectedException;
import com.g2rain.agent.runtime.engine.tools.guard.SourceVerifier;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.PlatformCatalog;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCategory;
import com.g2rain.agent.runtime.support.tool.BuiltinToolKind;
import com.g2rain.agent.runtime.support.tool.BuiltinTools;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 节点任务记忆：写 output / 便签，以及按需读证据报文。
 *
 * <p>output 须过契约白名单与来源校验。读报文走状态门面，Redis 未命中回源库表。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NodeBuiltinTools implements BuiltinTools {

    /**
     * 来源类型的可选值提示（MODEL 不可作为产出来源）。
     */
    private static final String TYPES_HINT = ModelHint.NEXT_OUTPUT_SOURCE_TYPE.text("types",
        Arrays.stream(SourceType.values()).filter(type -> type != SourceType.MODEL).map(Enum::name)
            .collect(Collectors.joining("、")));

    /**
     * 输出契约校验。
     */
    private final OutputContractValidator outputContractValidator;

    /**
     * 按需读取证据报文。
     */
    private final RuntimeStateFacade stateFacade;

    /**
     * 按来源类型和引用路径校验节点参数。
     */
    private final SourceVerifier sourceVerifier;

    /**
     * 平台字段目录：outputs 禁写与 catalog.keys() 同名键。
     */
    private final PlatformCatalog platformCatalog;

    /**
     * 写一条契约 output。键须在契约声明内，来源须通过校验。
     *
     * <p>校验不通过时抛出 {@link ToolRejectedException}，原因写明哪一项不符，下一步写明可用的键、来源类型或取值要求；
     * 产出非必填时另提示可以不写、直接结束节点。</p>
     *
     * @param key         output 键
     * @param value       值
     * @param sourceType  来源类型名
     * @param sourceRef   来源对象 id
     * @param sourcePath  来源路径；可空
     * @param toolContext Spring 工具上下文
     * @return 写入的键
     */
    @Tool(
        name = "builtin_output_set",
        description = """
            写入本节点一条 output，必须带来源
            """
    )
    @BuiltinToolKind(category = BuiltinToolCategory.NODE, action = BuiltinToolAction.OUTPUT)
    public KeyResult outputSet(@ToolParam(description = "output 键") String key,
                               @ToolParam(description = "值") JsonNode value,
                               @ToolParam(description = "来源类型") String sourceType,
                               @ToolParam(description = "来源对象 id") String sourceRef,
                               @ToolParam(required = false, description = "来源路径") @Nullable String sourcePath,
                               ToolContext toolContext) {
        ToolExecutionContext ctx = ToolExecutionContexts.requireSpring(toolContext);
        SkillContract contract = ctx.contract();
        if (Strings.isBlank(key)) {
            throw new ToolRejectedException(RuntimeErrorCode.OUTPUT_PATH_INVALID,
                outputContractValidator.keysHint(contract), "key 为空");
        }

        String trimmedKey = key.trim();
        if (platformCatalog.keys().contains(trimmedKey)) {
            throw new ToolRejectedException(RuntimeErrorCode.PLATFORM_FIELD_FORBIDDEN,
                outputContractValidator.keysHint(contract), trimmedKey);
        }

        outputContractValidator.requireDeclared(contract, trimmedKey);
        SourceType type = parseSourceType(sourceType, outputContractValidator.withOptional(contract, trimmedKey, TYPES_HINT));
        if (type == SourceType.MODEL) {
            throw new ToolRejectedException(RuntimeErrorCode.SOURCE_REJECT,
                outputContractValidator.withOptional(contract, trimmedKey, TYPES_HINT),
                "outputs." + trimmedKey + " 不能以 MODEL 作为来源");
        }

        if (Strings.isBlank(sourceRef)) {
            throw new ToolRejectedException(RuntimeErrorCode.SOURCE_REJECT,
                outputContractValidator.withOptional(contract, trimmedKey, ModelHint.NEXT_OUTPUT_SOURCE_REF.text()),
                "outputs." + trimmedKey + " 缺少 sourceRef");
        }

        String sp = Objects.toString(sourcePath, "");
        Source source = new Source(type, sourceRef.trim(), sp);
        Object plain = RuntimeUtils.plain(value);
        if (Objects.isNull(plain)) {
            throw new ToolRejectedException(RuntimeErrorCode.SOURCE_REJECT,
                outputContractValidator.withOptional(contract, trimmedKey, ModelHint.NEXT_OUTPUT_VALUE.text()),
                "outputs." + trimmedKey + " 缺少值");
        }
        outputContractValidator.requireAcceptedSource(contract, trimmedKey, source, plain);
        if (!sourceVerifier.verify(source, plain, ctx)) {
            throw new ToolRejectedException(RuntimeErrorCode.SOURCE_REJECT,
                outputContractValidator.withOptional(contract, trimmedKey, ModelHint.NEXT_OUTPUT_VALUE.text()),
                "outputs." + trimmedKey + " 的值与来源 " + type + " [" + source.ref() + "] " + sp + " 处的内容不一致");
        }

        ctx.scope().output(trimmedKey, new Sourced<>(plain, source));
        return new KeyResult(trimmedKey);
    }

    /**
     * 写节点私有便签。保留键（awaiting 等）不可写。
     *
     * @param key         便签键
     * @param value       值
     * @param toolContext Spring 工具上下文
     * @return 写入的键
     */
    @SuppressWarnings("UnusedReturnValue")
    @Tool(
        name = "builtin_state_set",
        description = """
            写入本节点私有便签
            """
    )
    @BuiltinToolKind(category = BuiltinToolCategory.NODE, action = BuiltinToolAction.WRITE)
    public KeyResult stateSet(@ToolParam(description = "便签键") String key,
                              @ToolParam(description = "值") JsonNode value,
                              ToolContext toolContext) {
        ToolExecutionContext ctx = ToolExecutionContexts.requireSpring(toolContext);
        if (Strings.isBlank(key)) {
            throw new ToolRejectedException(RuntimeErrorCode.OUTPUT_PATH_INVALID, ModelHint.NEXT_STATE_KEY.text(), "key 为空");
        }

        String trimmed = key.trim();
        for (NodeNoteKey reserved : NodeNoteKey.values()) {
            if (reserved.wire().equals(trimmed)) {
                throw new ToolRejectedException(RuntimeErrorCode.STATE_KEY_RESERVED, ModelHint.NEXT_STATE_KEY.text(), trimmed);
            }
        }

        ctx.scope().note(trimmed, RuntimeUtils.plain(value));
        return new KeyResult(trimmed);
    }

    /**
     * 按 evidenceId 读证据报文；热态未命中回源库表。
     *
     * @param evidenceId  证据 id
     * @param toolContext Spring 工具上下文
     * @return 报文节点
     */
    @Tool(
        name = "builtin_evidence_get",
        description = """
            按 evidenceId 读取证据报文
            """
    )
    @BuiltinToolKind(category = BuiltinToolCategory.NODE, action = BuiltinToolAction.READ)
    public JsonNode evidenceGet(@ToolParam(description = "证据 id") String evidenceId, ToolContext toolContext) {
        ToolExecutionContext ctx = ToolExecutionContexts.requireSpring(toolContext);
        if (Strings.isBlank(evidenceId)) {
            throw new ToolRejectedException(RuntimeErrorCode.EVIDENCE_PATH_INVALID, ModelHint.NEXT_EVIDENCE_ID.text(), "evidenceId 为空");
        }

        return stateFacade.payload(ctx.getFrame().turn(), ctx.getTaskId(), evidenceId.trim());
    }

    /**
     * 把调用方传入的来源类型字符串解析为枚举。
     *
     * @param raw  来源类型名；空白则拒绝
     * @param next 解析失败时给模型的下一步
     * @return 对应 {@link SourceType}
     */
    private static SourceType parseSourceType(@Nullable String raw, String next) {
        if (Strings.isBlank(raw)) {
            throw new ToolRejectedException(RuntimeErrorCode.SOURCE_REJECT, next, "sourceType 为空");
        }

        try {
            return SourceType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.error("failed to parseSourceType", ex);
            throw new ToolRejectedException(RuntimeErrorCode.SOURCE_REJECT, next, "来源类型 [" + raw + "] 不存在");
        }
    }

    /**
     * 写 output / 便签成功后的回包，避免裸字符串。
     *
     * @param key 写入的键
     */
    public record KeyResult(String key) {

    }
}
