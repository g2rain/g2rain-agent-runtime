package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.domain.hydrate.ArgSpec;
import com.g2rain.agent.runtime.domain.hydrate.BindingWhen;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.hydrate.ValueRef;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.SourceType;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.engine.tools.ToolInterceptor;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;


/**
 * 调用前按当前工具 {@code args.from / const} 填写入参，覆盖模型原值；证据绑定写入 Source。
 *
 * <p>内置工具跳过。绑定条件不满足时保留原值。例：{@code from: outputs.orderId} 用上游产出覆盖模型猜测。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Order(2)
@Component
@RequiredArgsConstructor
public class ArgBindingInterceptor implements ToolInterceptor {

    /**
     * 证据读取：按 {@code from: evidence.*} 取历史调用结果。
     */
    private final EvidenceReader evidenceReader;

    /**
     * JSON 编解码。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 调用前按契约 {@code from / const} 覆盖入参，并把来源写入请求。
     *
     * <p>内置工具跳过。可从证据、平台、任务记忆、会话记忆或任务前情取值；
     * 绑定条件不满足时保留模型原值。本方法始终放行，不短路调用。</p>
     *
     * @param request 本次工具调用请求
     * @return 始终为空，表示不阻断
     */
    @Override
    public Optional<Observation> before(ToolInvokeRequest request) {
        if (ToolCallbacks.isBuiltin(request.toolCallback())) {
            return Optional.empty();
        }

        ToolExecutionContext ctx = request.context();
        ToolSpec spec = request.contract();
        if (Objects.isNull(spec) || Collections.isEmpty(spec.getArgs())) {
            return Optional.empty();
        }

        JsonNode parsed = JSON.str2node(request.arguments());
        if (!(parsed instanceof ObjectNode args)) {
            return Optional.empty();
        }

        // 逐参按契约注入：常量 → 数组模板 → from 取值，命中即改写并记来源
        JsonNode working = WorkingTree.of(ctx);
        boolean changed = false;
        for (Map.Entry<String, ArgSpec> entry : spec.getArgs().entrySet()) {
            String param = Strings.trim(entry.getKey());
            ArgSpec binding = entry.getValue();
            if (Strings.isBlank(param)) {
                continue;
            }

            // 契约常量直接覆盖模型值
            if (Objects.nonNull(binding.getConstant())) {
                if (JSON.upsertNode(args, param, binding.getConstant(), true)) {
                    changed = true;
                    request.argSource(param, new Source(SourceType.CONTRACT, "const", param));
                }

                continue;
            }

            String fromPath = Strings.trim(binding.getFrom());
            if (Strings.isBlank(fromPath)) {
                continue;
            }

            // 数组模板：按来源数组逐行展开到目标数组
            if (isArrayTemplate(fromPath, param)) {
                if (applyArrayTemplate(param, args, working, request, binding)) {
                    changed = true;
                }

                continue;
            }

            // 普通 from：取到值才覆盖，取不到保留模型原值
            BoundValue bound = readFrom(binding, binding.whenOrDefault(), working, ctx);
            if (Objects.nonNull(bound) && JSON.hasValue(bound.value())
                && JSON.upsertNode(args, param, bound.value(), true)) {
                changed = true;
                if (Objects.nonNull(bound.source())) {
                    request.argSource(param, bound.source());
                }
            }
        }

        // 有改写才回写入参，并触发指纹重算
        if (changed) {
            request.replaceArguments(JSON.obj2str(args));
            log.debug("[ArgBinding] rewritten tool={}", request.toolName());
        }

        return Optional.empty();
    }

    /**
     * 按 from 从工作树、证据或平台取值。
     *
     * @param binding 参数契约
     * @param when    绑定时机
     * @param working 当前节点取值树
     * @param ctx     执行上下文
     * @return 绑定值与来源；取不到为 {@code null}
     */
    private @Nullable BoundValue readFrom(ArgSpec binding, BindingWhen when, JsonNode working, ToolExecutionContext ctx) {
        ValueRef ref = binding.getValueRef();
        if (Objects.isNull(ref)) {
            return null;
        }

        return switch (ref.bag()) {
            // 证据：UNIQUE 时要求命中唯一一条
            case EVIDENCE -> {
                Optional<EvidenceReader.Hit> hit = evidenceReader.resolve(ctx, ref);
                if (hit.isEmpty()) {
                    yield null;
                }
                EvidenceReader.Hit resolved = hit.get();
                if (when == BindingWhen.UNIQUE && !isUniqueArray(resolved.value())) {
                    yield null;
                }
                yield new BoundValue(resolved.value(), resolved.source());
            }
            // 平台快照：按键直取
            case PLATFORM -> {
                String key = ref.path();
                JsonNode value = ctx.getFrame().turn().platform().get(key).orElse(null);
                if (!JSON.hasValue(value)) {
                    yield null;
                }
                yield new BoundValue(value, new Source(SourceType.PLATFORM, key, key));
            }
            // 工作树（输入/产出/共享/会话/前情）：UNIQUE 时先确认可唯一定位
            default -> {
                if (when == BindingWhen.UNIQUE && !allowsWhen(working, ref)) {
                    yield null;
                }
                JsonNode value = WorkingTree.lookup(working, ref);
                if (!JSON.hasValue(value)) {
                    yield null;
                }
                SourceType type = sourceTypeOf(ref.bag());
                yield new BoundValue(value, new Source(type, refLeaf(ref.path()), ref.path()));
            }
        };
    }

    /**
     * 把数组通配 from 展开写入对应下标参数。
     *
     * @param toPath  目标路径模板
     * @param args    当前入参对象
     * @param working 当前节点取值树
     * @param request 调用请求（写来源）
     * @param binding 参数契约
     * @return 是否改写了入参
     */
    private static boolean applyArrayTemplate(String toPath, ObjectNode args, JsonNode working, ToolInvokeRequest request, ArgSpec binding) {
        ValueRef baseRef = binding.getValueRef();
        if (Objects.isNull(baseRef)) {
            return false;
        }

        int wild = baseRef.path().indexOf(RuntimeUtils.WILDCARD_ARRAY_INDEX);
        if (wild < 0) {
            return false;
        }

        ValueRef arrayRef = new ValueRef(baseRef.bag(), baseRef.path().substring(0, wild));
        JsonNode sourceArr = WorkingTree.lookup(working, arrayRef);
        if (!(sourceArr instanceof ArrayNode src) || src.isEmpty()) {
            return false;
        }

        boolean changed = false;
        for (int i = 0, j = src.size(); i < j; i++) {
            String to = toPath.replace(RuntimeUtils.WILDCARD_ARRAY_INDEX, "[" + i + "]");
            if (JSON.hasValue(JSON.lookupNode(args, to))) {
                continue;
            }

            String itemPath = baseRef.path().replace(RuntimeUtils.WILDCARD_ARRAY_INDEX, "[" + i + "]");
            ValueRef itemRef = new ValueRef(baseRef.bag(), itemPath);
            JsonNode v = WorkingTree.lookup(working, itemRef);
            if (JSON.hasValue(v) && JSON.upsertNode(args, to, v, true)) {
                changed = true;
                request.argSource(to, new Source(sourceTypeOf(itemRef.bag()), refLeaf(itemRef.path()), itemRef.path()));
            }
        }

        return changed;
    }

    /**
     * from 与 to 是否都带数组通配。
     *
     * @param fromPath 来源路径
     * @param toPath   目标路径
     * @return 两侧都含 {@code []}
     */
    private static boolean isArrayTemplate(String fromPath, String toPath) {
        return Strings.contains(fromPath, RuntimeUtils.WILDCARD_ARRAY_INDEX) && Strings.contains(toPath, RuntimeUtils.WILDCARD_ARRAY_INDEX);
    }

    /**
     * UNIQUE 绑定时，路径所属数组是否恰好一条。
     *
     * @param working 当前节点取值树
     * @param ref     取值引用
     * @return 非数组路径放行；数组仅一条为 {@code true}
     */
    private static boolean allowsWhen(JsonNode working, ValueRef ref) {
        String path = ref.path();
        int bracket = path.indexOf('[');
        if (bracket <= 0) {
            return true;
        }

        ValueRef arrayRef = new ValueRef(ref.bag(), path.substring(0, bracket));
        return isUniqueArray(WorkingTree.lookup(working, arrayRef));
    }

    /**
     * 节点是否为长度恰好 1 的数组。
     *
     * @param node JSON 节点；可空
     * @return 单元素数组为 {@code true}
     */
    private static boolean isUniqueArray(@Nullable JsonNode node) {
        return node instanceof ArrayNode arr && arr.size() == 1;
    }

    /**
     * 取值袋到 {@link SourceType}。
     *
     * @param bag 取值袋
     * @return 对应来源类型
     */
    private static SourceType sourceTypeOf(ValueRef.Bag bag) {
        return switch (bag) {
            case OUTPUTS, INPUTS -> SourceType.NODE_OUTPUT;
            case SHARED -> SourceType.SHARED;
            case SESSION -> SourceType.SESSION;
            case PRIOR -> SourceType.PRIOR_TASK;
            case PLATFORM -> SourceType.PLATFORM;
            case EVIDENCE -> SourceType.EVIDENCE;
        };
    }

    /**
     * 取路径第一段叶子，去掉点号与下标。
     *
     * @param path 引用路径
     * @return 叶子键；空白路径为空串
     */
    private static String refLeaf(String path) {
        if (Strings.isBlank(path)) {
            return "";
        }

        int dot = path.indexOf('.');
        int bracket = path.indexOf('[');
        int cut = dot < 0 ? path.length() : dot;
        if (bracket >= 0 && bracket < cut) {
            cut = bracket;
        }

        return path.substring(0, cut);
    }

    /**
     * 一次绑定得到的值与来源。
     *
     * @param value  绑定值
     * @param source 来源；平台/证据可空以外一般有值
     */
    private record BoundValue(JsonNode value, @Nullable Source source) {

    }
}
