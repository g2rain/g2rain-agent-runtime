package com.g2rain.agent.runtime.engine.tools.builtin;


import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.SourceType;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.state.OutputContractValidator;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.ToolExecutionContexts;
import com.g2rain.agent.runtime.engine.tools.guard.SourceVerifier;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.platform.PlatformCatalog;
import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCategory;
import com.g2rain.agent.runtime.support.tool.BuiltinToolKind;
import com.g2rain.agent.runtime.support.tool.BuiltinTools;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Objects;

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
public class NodeBuiltinTools implements BuiltinTools {

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
    @BuiltinToolKind(category = BuiltinToolCategory.NODE, action = BuiltinToolAction.WRITE)
    public KeyResult outputSet(@ToolParam(description = "output 键") String key,
                               @ToolParam(description = "值") Object value,
                               @ToolParam(description = "来源类型") String sourceType,
                               @ToolParam(description = "来源对象 id") String sourceRef,
                               @ToolParam(required = false, description = "来源路径") @Nullable String sourcePath,
                               ToolContext toolContext) {
        ToolExecutionContext ctx = ToolExecutionContexts.requireSpring(toolContext);
        if (Strings.isBlank(key)) {
            throw new BusinessException(RuntimeErrorCode.OUTPUT_PATH_INVALID, "key");
        }

        String trimmedKey = key.trim();
        if (platformCatalog.keys().contains(trimmedKey)) {
            throw new BusinessException(RuntimeErrorCode.PLATFORM_FIELD_FORBIDDEN, trimmedKey);
        }
        outputContractValidator.requireDeclared(ctx.contract(), trimmedKey);
        SourceType type = parseSourceType(sourceType);
        if (type == SourceType.MODEL) {
            throw new BusinessException(RuntimeErrorCode.SOURCE_REJECT, trimmedKey);
        }
        if (Strings.isBlank(sourceRef)) {
            throw new BusinessException(RuntimeErrorCode.SOURCE_REJECT, trimmedKey);
        }

        String sp = Objects.toString(sourcePath, "");
        Source source = new Source(type, sourceRef.trim(), sp);
        outputContractValidator.requireAcceptedSource(ctx.contract(), trimmedKey, source, value);
        if (!sourceVerifier.verify(source, value, ctx)) {
            throw new BusinessException(RuntimeErrorCode.SOURCE_REJECT, trimmedKey);
        }
        ctx.scope().output(trimmedKey, new Sourced<>(value, source));
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
                              @ToolParam(description = "值") Object value,
                              ToolContext toolContext) {
        ToolExecutionContext ctx = ToolExecutionContexts.requireSpring(toolContext);
        if (Strings.isBlank(key)) {
            throw new BusinessException(RuntimeErrorCode.OUTPUT_PATH_INVALID, "key");
        }

        String trimmed = key.trim();
        for (NodeNoteKey reserved : NodeNoteKey.values()) {
            if (reserved.wire().equals(trimmed)) {
                throw new BusinessException(RuntimeErrorCode.STATE_KEY_RESERVED, trimmed);
            }
        }

        ctx.scope().note(trimmed, value);
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
            throw new BusinessException(RuntimeErrorCode.EVIDENCE_PATH_INVALID, "evidenceId");
        }

        return stateFacade.payload(ctx.getFrame().turn(), ctx.getTaskId(), evidenceId.trim());
    }

    /**
     * 把调用方传入的来源类型字符串解析为枚举。
     *
     * @param raw 来源类型名；空白则拒绝
     * @return 对应 {@link SourceType}
     */
    private static SourceType parseSourceType(@Nullable String raw) {
        if (Strings.isBlank(raw)) {
            throw new BusinessException(RuntimeErrorCode.SOURCE_REJECT, "sourceType");
        }

        try {
            return SourceType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(RuntimeErrorCode.SOURCE_REJECT, raw);
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
