package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.spec.ArgSpec;
import com.g2rain.agent.runtime.spec.BindingWhen;
import com.g2rain.agent.runtime.spec.ToolSpec;
import com.g2rain.agent.runtime.spec.ValueRef;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.spec.SourceType;
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
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;


/**
 * 调用前按当前工具 {@code args.from / const} 填写入参，覆盖模型原值；证据绑定写入 Source。
 *
 * <p>内置工具跳过。绑定条件不满足时保留原值。例：{@code from: outputs.orderId} 用上游产出覆盖模型猜测。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Order(2000)
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
     * 路径分段符。
     */
    private static final char PATH_SEPARATOR = '.';

    /**
     * 数组下标起始符。
     */
    private static final char INDEX_OPEN = '[';

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

        // 已批准写工具按用户确认的那组参数执行：这组参数在确认前已规范、绑定、校验过，不得再改写或拒调
        if (request.context().approvedWrite()) {
            return Optional.empty();
        }

        ToolExecutionContext ctx = request.context();
        ToolSpec spec = request.contract();
        if (Objects.isNull(spec) || Collections.isEmpty(spec.getArgs())) {
            return Optional.empty();
        }

        // 在请求持有的参数树上原地改写，不再解析与回写字符串
        ObjectNode args = request.args();

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

        if (changed) {
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
     * 把数组通配 from 展开写入对应下标参数；支持多层嵌套，各层下标按来源数组的实际长度分别展开。
     *
     * <p>{@code evidence.*} 先取出一条证据里该抽取项的整棵子树，再在其上展开，所有行都来自同一次调用结果，
     * 行与行天然对齐。例：{@code invoiceDetailList[].goodsName} ← {@code evidence.order_lines[].goodsName}。</p>
     *
     * @param toPath  目标路径模板
     * @param args    当前入参对象
     * @param working 当前节点取值树
     * @param request 调用请求（写来源）
     * @param binding 参数契约
     * @return 是否改写了入参
     */
    private boolean applyArrayTemplate(String toPath, ObjectNode args, JsonNode working, ToolInvokeRequest request, ArgSpec binding) {
        ValueRef baseRef = binding.getValueRef();
        if (Objects.isNull(baseRef)) {
            return false;
        }

        TemplateSource template = baseRef.bag() == ValueRef.Bag.EVIDENCE
            ? evidenceTemplate(request.context(), baseRef.path())
            : workingTemplate(working, baseRef.bag());
        if (Objects.isNull(template)) {
            return false;
        }

        boolean changed = false;
        for (PathPair pair : concretePaths(baseRef.path(), toPath, template.root())) {
            // 目标位置已有值（模型或前一条绑定给出）则保留
            if (JSON.hasValue(JSON.lookupNode(args, pair.to()))) {
                continue;
            }

            JsonNode value = JSON.lookupNode(template.root(), pair.from());
            if (JSON.hasValue(value) && JSON.upsertNode(args, pair.to(), value, true)) {
                changed = true;
                request.argSource(pair.to(), template.sourceOf(pair.from()));
            }
        }

        return changed;
    }

    /**
     * 证据数组模板的取值根：按抽取键取一条证据的抽取子树，挂在同名键下，模板路径无需改写。
     *
     * @param ctx  执行上下文
     * @param path 来源路径，如 {@code order_lines[].goodsName}
     * @return 取值根与来源；取不到证据为 {@code null}
     */
    private @Nullable TemplateSource evidenceTemplate(ToolExecutionContext ctx, String path) {
        String key = extractKeyOf(path);
        if (Strings.isBlank(key)) {
            return null;
        }

        Optional<EvidenceReader.Hit> hit = evidenceReader.resolve(ctx, new ValueRef(ValueRef.Bag.EVIDENCE, key));
        if (hit.isEmpty()) {
            return null;
        }

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set(key, hit.get().value());
        Source extract = hit.get().source();
        return new TemplateSource(root, itemPath -> new Source(SourceType.EVIDENCE, extract.ref(),
            extract.path() + itemPath.substring(key.length())));
    }

    /**
     * 工作树数组模板的取值根：对应袋（输入、产出、共享、会话、前情）的子树。
     *
     * @param working 当前节点取值树
     * @param bag     来源袋
     * @return 取值根与来源；袋不存在为 {@code null}
     */
    private static @Nullable TemplateSource workingTemplate(JsonNode working, ValueRef.Bag bag) {
        JsonNode root = working.get(bag.wirePrefix());
        if (Objects.isNull(root)) {
            return null;
        }

        SourceType type = sourceTypeOf(bag);
        return new TemplateSource(root, itemPath -> new Source(type, refLeaf(itemPath), itemPath));
    }

    /**
     * 证据路径的抽取键：第一个 {@code .} 或 {@code [} 之前的部分。
     *
     * @param path 来源路径
     * @return 抽取键
     */
    private static String extractKeyOf(String path) {
        int end = path.length();
        int dot = path.indexOf(PATH_SEPARATOR);
        if (dot >= 0) {
            end = dot;
        }

        int bracket = path.indexOf(INDEX_OPEN);
        if (bracket >= 0 && bracket < end) {
            end = bracket;
        }

        return path.substring(0, end);
    }

    /**
     * 把含通配的来源 / 目标路径展开成逐项对应的具体路径；用队列逐层展开，不递归。
     *
     * <p>每次取来源路径里第一个 {@code []}，按该处数组的实际长度生成新路径，来源与目标的第一个 {@code []}
     * 换成同一个下标。例：{@code items[].tags[]}，items 两项、各有 3 和 1 个 tag，得到 4 对路径。
     * 来源层数用完而目标仍带通配时，层数对不上，该对路径丢弃。</p>
     *
     * @param fromPath 来源路径模板，含通配
     * @param toPath   目标路径模板
     * @param root     来源路径的取值根
     * @return 具体路径对，按展开顺序
     */
    static List<PathPair> concretePaths(String fromPath, String toPath, JsonNode root) {
        List<PathPair> pairs = new ArrayList<>();
        Deque<PathPair> pending = new ArrayDeque<>();
        pending.add(new PathPair(fromPath, toPath));
        while (!pending.isEmpty()) {
            PathPair pair = pending.poll();
            int wild = pair.from().indexOf(RuntimeUtils.WILDCARD_ARRAY_INDEX);
            if (wild < 0) {
                if (!pair.to().contains(RuntimeUtils.WILDCARD_ARRAY_INDEX)) {
                    pairs.add(pair);
                }
                continue;
            }

            JsonNode array = JSON.lookupNode(root, pair.from().substring(0, wild));
            if (!(array instanceof ArrayNode items)) {
                continue;
            }

            for (int i = 0, j = items.size(); i < j; i++) {
                String index = "[" + i + "]";
                pending.add(new PathPair(firstWildcardAs(pair.from(), index), firstWildcardAs(pair.to(), index)));
            }
        }

        return pairs;
    }

    /**
     * 把路径中第一个 {@code []} 换成具体下标；没有通配时原样返回。
     *
     * @param path  路径
     * @param index 具体下标，如 {@code [0]}
     * @return 替换后的路径
     */
    private static String firstWildcardAs(String path, String index) {
        int at = path.indexOf(RuntimeUtils.WILDCARD_ARRAY_INDEX);
        return at < 0 ? path : path.substring(0, at) + index + path.substring(at + RuntimeUtils.WILDCARD_ARRAY_INDEX.length());
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
     * 数组模板的取值根与逐项来源。
     *
     * @param root       来源路径的取值根
     * @param itemSource 按具体来源路径生成该项的来源
     */
    private record TemplateSource(JsonNode root, Function<String, Source> itemSource) {

        /**
         * 生成具体来源路径对应的来源。
         *
         * @param itemPath 具体来源路径，如 {@code order_lines[0].goodsName}
         * @return 该项来源
         */
        Source sourceOf(String itemPath) {
            return itemSource.apply(itemPath);
        }
    }

    /**
     * 一次绑定得到的值与来源。
     *
     * @param value  绑定值
     * @param source 来源；平台/证据可空以外一般有值
     */
    private record BoundValue(JsonNode value, @Nullable Source source) {

    }

    /**
     * 一对逐项对应的具体路径。
     *
     * @param from 来源路径（数据袋内）
     * @param to   目标参数路径
     */
    record PathPair(String from, String to) {

    }
}
