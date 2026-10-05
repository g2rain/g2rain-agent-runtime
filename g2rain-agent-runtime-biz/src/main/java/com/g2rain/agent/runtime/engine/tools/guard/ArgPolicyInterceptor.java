package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.domain.hydrate.ArgSpec;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.hydrate.ValueRef;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.engine.tools.ToolInterceptor;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.JsonSchemaSupport;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecBuilder;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.json.NestedJsonStringModule;
import com.g2rain.common.json.ObjectTreeRewriter;
import com.g2rain.common.utils.Strings;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 入参策略门：拒绝未解析引用、收窄 schema、校验必填后写回 arguments。
 *
 * <p>非法 JSON、未解析 {@code $bag.path}、缺必填则拒调。内置工具跳过。平台值只走契约 {@code from}。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Order(1)
@Component
public class ArgPolicyInterceptor implements ToolInterceptor {

    /**
     * 工具入参编解码（支持嵌套 JSON 字符串叶子）
     */
    private static final JsonCodec TOOL_ARGS_CODEC = JsonCodecBuilder.builder()
        .withConfig(builder -> builder.addModule(new NestedJsonStringModule()))
        .withDefaults().build();

    /**
     * 通用 JSON 编解码
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 净化并校验业务工具入参。
     *
     * @param request 本次工具调用请求
     * @return 空=放行；有值=拒调观察
     */
    @Override
    public Optional<Observation> before(ToolInvokeRequest request) {
        // 已批准写工具按原参执行，不得再改写入参
        // Spring @Tool 内置回调入参由各自 @ToolParam 约束，不走业务 schema 门
        if (ToolCallbacks.isBuiltin(request.toolCallback())) {
            return Optional.empty();
        }

        Optional<Observation> blocked = prepareArguments(request);
        if (blocked.isPresent()) {
            Observation obs = blocked.get();
            log.info("[ArgPolicy] reject tool={} errorCode={} message={}",
                request.toolName(), obs.errorCode(), obs.message()
            );
        } else {
            log.info("[ArgPolicy] pass tool={}", request.toolName());
        }
        return blocked;
    }

    /**
     * 解析 → 拒 $ref 字面量 → schema 收窄 → 写回。
     *
     * @param request 本次工具调用请求
     * @return 空=放行；有值=拒调观察
     */
    private static Optional<Observation> prepareArguments(ToolInvokeRequest request) {
        // 非法 JSON 不抛出，写回失败观察供模型重交；空/空白入参按 {} 继续走 required 校验
        JsonNode args;
        try {
            String raw = request.arguments();
            if (Strings.isBlank(raw)) {
                args = JSON.str2obj("{}", JsonNode.class);
            } else {
                args = TOOL_ARGS_CODEC.str2obj(raw, JsonNode.class);
            }

            if (Objects.isNull(args) || args.isNull()) {
                args = JSON.str2obj("{}", JsonNode.class);
            }
        } catch (RuntimeException ex) {
            String message = Objects.requireNonNullElse(ex.getMessage(), ex.getClass().getSimpleName());
            return Optional.of(Observation.failure(
                ModelHint.ARG_JSON_INVALID.text("detail", message),
                RuntimeErrorCode.ARG_JSON_INVALID.code()
            ));
        }

        // 契约已走 from；入参里残留 $bag.path 一律拒调，不再软替换
        List<String> unresolved = collectUnresolvedRefs(args);
        if (!unresolved.isEmpty()) {
            return Optional.of(Observation.failure(
                ModelHint.ARG_UNRESOLVED_REF.text("refs", String.join(", ", unresolved)),
                RuntimeErrorCode.ARG_UNRESOLVED_REF.code()
            ));
        }

        // schema 门要求根为对象
        if (!(args instanceof ObjectNode object)) {
            return reject(ModelHint.ARG_NOT_OBJECT.text());
        }

        // 按 schema 删多余顶层键、删非必填 null；有 from/const 的字段由 ArgBinding 注入，此处不计缺
        ToolSpec contract = request.contract();
        List<String> missing = JsonSchemaSupport.narrowTopLevel(object, schemaOf(request)).stream()
            .filter(field -> !boundFromContract(contract, field))
            .toList();
        if (!missing.isEmpty()) {
            return reject(ModelHint.ARG_MISSING_REQUIRED.text("fields", String.join(", ", missing)));
        }

        // 净化后的对象写回 request，供后续 dispatch
        request.replaceArguments(JSON.obj2str(object));
        return Optional.empty();
    }

    /**
     * 收集 {@code $bag.path} 字面量叶子路径列表（兼容残留，一律拒调）。
     *
     * @param root 入参树；可空
     * @return 未解析引用描述列表
     */
    private static List<String> collectUnresolvedRefs(@Nullable JsonNode root) {
        if (Objects.isNull(root)) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        ObjectTreeRewriter.forEachLeaf(root, (path, leaf) -> {
            if (!leaf.isString()) {
                return;
            }

            String raw = leaf.asString();
            if (Strings.isBlank(raw)) {
                return;
            }

            String value = raw.trim();
            if (Objects.isNull(dollarRef(value))) {
                return;
            }

            // 根级引用只记 ref；嵌套记 path=ref
            out.add(path.isEmpty() ? value : path + "=" + value);
        });

        return List.copyOf(out);
    }

    /**
     * {@code $platform.x} / {@code $inputs.x} 等 → ValueRef；非 $ 引用返回 null。
     *
     * @param raw 叶子字符串；可空
     * @return 解析后的引用；非 $ 引用为 {@code null}
     */
    private static @Nullable ValueRef dollarRef(@Nullable String raw) {
        String trimmed = Strings.trim(raw);
        if (Strings.isBlank(trimmed) || !trimmed.startsWith("$")) {
            return null;
        }

        String expr = trimmed.substring(1);
        if (!ValueRef.isKnown(expr)) {
            return null;
        }

        return ValueRef.parse(expr);
    }

    /**
     * 取工具 inputSchema 节点；非法或空白视为无 schema。
     *
     * @param request 本次工具调用请求
     * @return schema 根；可空
     */
    private static @Nullable JsonNode schemaOf(ToolInvokeRequest request) {
        ToolCallback callback = request.toolCallback();
        String json = callback.getToolDefinition().inputSchema();
        if (Strings.isBlank(json)) {
            return null;
        }

        try {
            return JSON.str2obj(json, JsonNode.class);
        } catch (RuntimeException ex) {
            // schema 非法时视为无 schema，narrowTopLevel 会跳过收窄
            return null;
        }
    }

    /**
     * 统一 ARG_REJECTED 失败观察
     *
     * @param reason 拒调原因
     * @return 失败观测
     */
    private static Optional<Observation> reject(String reason) {
        return Optional.of(Observation.failure(reason, RuntimeErrorCode.ARG_REJECTED.code()));
    }

    /**
     * 契约已声明 from 或 const 的顶层参数由绑定补值，ArgPolicy 不计缺必填。
     *
     * @param contract 工具契约；可空
     * @param field    顶层参数名
     * @return 将由绑定补值
     */
    private static boolean boundFromContract(@Nullable ToolSpec contract, String field) {
        if (Objects.isNull(contract) || Strings.isBlank(field)) {
            return false;
        }

        ArgSpec arg = contract.getArgs().get(field);
        if (Objects.isNull(arg)) {
            return false;
        }

        return Objects.nonNull(arg.getValueRef()) || Objects.nonNull(arg.getConstant());
    }
}
