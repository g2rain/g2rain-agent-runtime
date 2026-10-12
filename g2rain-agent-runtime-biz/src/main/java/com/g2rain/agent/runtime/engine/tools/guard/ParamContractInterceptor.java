package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.domain.hydrate.ArgSpec;
import com.g2rain.agent.runtime.domain.hydrate.MissingActionType;
import com.g2rain.agent.runtime.domain.hydrate.MissingSpec;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.hydrate.ValueRef;
import com.g2rain.agent.runtime.domain.state.SourceType;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.engine.tools.ToolInterceptor;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;


/**
 * 参数契约门：按契约注入来源、校验溯源；缺失或来源不可信时拒调，并在观测中给出下一步。
 *
 * <p>可从 PLATFORM / NODE_OUTPUT / CONTRACT 注入；forbidSynthetic 时拒绝模型编造。
 * 缺失按 missing 配置 ASK_USER / CALL_TOOL / FAIL。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Order(3)
@Component
@RequiredArgsConstructor
public class ParamContractInterceptor implements ToolInterceptor {

    /**
     * 入参可信来源判定。
     */
    private final SourceResolver sourceResolver;

    /**
     * JSON 编解码
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * GateAccumulator（门禁累计器）：遍历契约时收集追问字段 / 失败原因 / 须调工具 / 是否改写过入参。
     */
    private static final class GateAccumulator {

        /**
         * 须追问的字段名
         */
        final List<String> askUser = new ArrayList<>();

        /**
         * 平台缺上下文等原因
         */
        @Nullable String failReason;

        /**
         * 须先调用的工具码
         */
        @Nullable String callTool;

        /**
         * 须先调工具补齐的参数路径
         */
        @Nullable String callToolPath;

        /**
         * 入参是否被注入改写
         */
        boolean changed;
    }

    /**
     * 套用参数契约：注入、校验或拒调。
     *
     * @param request 本次工具调用请求
     * @return 空=放行；有值=拒调观察
     */
    @Override
    public Optional<Observation> before(ToolInvokeRequest request) {
        // 已批准写工具按原参执行，不得再注入或拒调
        // Spring @Tool 内置回调参数由 @ToolParam 约束，不走 parameterContracts
        if (ToolCallbacks.isBuiltin(request.toolCallback())) {
            return Optional.empty();
        }

        ToolSpec spec = request.contract();
        if (Objects.isNull(spec) || Collections.isEmpty(spec.getArgs())) {
            return Optional.empty();
        }
        Map<String, ArgSpec> contracts = spec.getArgs();

        ToolExecutionContext ctx = request.context();
        JsonNode parsed = JSON.str2node(request.arguments());
        if (!(parsed instanceof ObjectNode args)) {
            return Optional.empty();
        }

        JsonNode working = WorkingTree.of(ctx);
        GateAccumulator acc = new GateAccumulator();
        log.debug("[ParamContract] begin tool={} contractKeys={}", request.toolName(), contracts.keySet());

        // 任一契约产生即时拒调则短路（如数组元素缺参 CALL_TOOL、forbidSynthetic 拒调）
        Optional<Observation> reject = contracts.entrySet().stream()
            .map(e -> applyContract(e.getKey(), e.getValue(), args, working, request, ctx, acc))
            .flatMap(Optional::stream).findFirst();
        if (reject.isPresent()) {
            Observation blocked = reject.get();
            log.debug("[ParamContract] reject tool={} errorCode={} message={}",
                request.toolName(), blocked.errorCode(), blocked.message()
            );
            return reject;
        }

        if (acc.changed) {
            request.replaceArguments(JSON.obj2str(args));
            log.debug("[ParamContract] rewritten arguments tool={}", request.toolName());
        }

        if (Objects.nonNull(acc.failReason)) {
            log.debug("[ParamContract] PARAM_PROVENANCE_FAIL tool={} reason={}",
                request.toolName(), acc.failReason
            );
            return Optional.of(Observation.rejected(
                RuntimeErrorCode.PARAM_PROVENANCE_FAIL, acc.failReason, ModelHint.NEXT_FAIL.text()
            ));
        }

        if (Objects.nonNull(acc.callTool)) {
            Observation rejected = Observation.rejected(
                RuntimeErrorCode.PARAM_PROVENANCE_CALL_TOOL,
                ModelHint.PARAM_CALL_TOOL.text("path", Objects.toString(acc.callToolPath, ""), "tool", acc.callTool),
                ModelHint.NEXT_CALL_TOOL.text("tool", acc.callTool)
            );
            log.debug("[ParamContract] PARAM_PROVENANCE_CALL_TOOL tool={} callTool={} message={}",
                request.toolName(), acc.callTool, rejected.message()
            );
            return Optional.of(rejected);
        }

        if (!acc.askUser.isEmpty()) {
            Observation rejected = Observation.rejected(
                RuntimeErrorCode.PARAM_PROVENANCE_ASK_USER,
                ModelHint.PARAM_ASK_USER.text("fields", String.join(",", acc.askUser)),
                ModelHint.NEXT_ASK_USER.text()
            );
            log.debug("[ParamContract] PARAM_PROVENANCE_ASK_USER tool={} askUser={} message={}",
                request.toolName(), acc.askUser, rejected.message()
            );
            return Optional.of(rejected);
        }

        log.debug("[ParamContract] pass tool={}", request.toolName());
        return Optional.empty();
    }

    /**
     * 套用单条参数契约（常量注入 / 数组展开 / 逐路径校验）。
     *
     * @param paramPath 参数路径（可含 []）
     * @param contract  契约
     * @param args      入参对象
     * @param working   当前节点取值树
     * @param request   调用请求（写来源）
     * @param ctx       执行上下文
     * @param acc       累计器
     * @return 即时拒调观察；无则空
     */
    private Optional<Observation> applyContract(String paramPath, ArgSpec contract, ObjectNode args, JsonNode working,
                                                ToolInvokeRequest request, ToolExecutionContext ctx, GateAccumulator acc) {

        if (Objects.nonNull(contract.getConstant()) && !Strings.contains(paramPath, RuntimeUtils.WILDCARD_ARRAY_INDEX)) {
            if (JSON.upsertNode(args, paramPath, contract.getConstant(), true)) {
                acc.changed = true;
            }

            return Optional.empty();
        }

        if (Strings.contains(paramPath, RuntimeUtils.WILDCARD_ARRAY_INDEX)) {
            Optional<Observation> lengthReject = checkArrayLength(paramPath, contract, args, working);
            if (lengthReject.isPresent()) {
                return lengthReject;
            }
        }

        List<String> paths = listPaths(paramPath, contract, args, working);
        if (paths.isEmpty()) {
            // 数组双方皆空：仅必填走缺参动作
            if (contract.isRequired()) {
                return handleMissing(paramPath, contract, RuntimeUtils.leafKey(paramPath), ctx, acc);
            }
            return Optional.empty();
        }

        boolean arrayContract = Strings.contains(paramPath, RuntimeUtils.WILDCARD_ARRAY_INDEX);
        String rowField = arrayContract ? RuntimeUtils.leafKey(paramPath) : null;
        JsonNode workingRows = arrayContract ? readWorkingCollection(contract, working, paramPath) : null;
        Optional<Observation> pathReject = IntStream.range(0, paths.size())
            .mapToObj(i -> validatePath(paths.get(i), contract, args, working, workingRows, rowField, i, request, ctx, acc))
            .flatMap(Optional::stream)
            .findFirst();

        if (pathReject.isPresent()) {
            return pathReject;
        }

        if (Objects.isNull(acc.callTool) && arrayContract && missingAction(contract) == MissingActionType.CALL_TOOL) {
            acc.callTool = missingTool(contract);
        }

        return Optional.empty();
    }

    /**
     * 展开参数路径：标量返回单路径；数组按 arg/working 最大长度展开为 [0]..[n)。
     *
     * @param paramPath 参数路径，可含 {@code []}
     * @param contract  参数契约
     * @param args      当前入参
     * @param working   当前节点取值树
     * @return 展开后的具体路径；无法展开为空列表
     */
    private static List<String> listPaths(String paramPath, ArgSpec contract, ObjectNode args, JsonNode working) {
        if (!Strings.contains(paramPath, RuntimeUtils.WILDCARD_ARRAY_INDEX)) {
            return List.of(paramPath);
        }

        String container = arrayContainerPath(paramPath);
        if (Strings.isBlank(container)) {
            return List.of();
        }

        // 入参与 Material 两侧数组取较大长度，逐下标展开 [] 通配
        int argSize = JSON.lookupNode(args, container) instanceof ArrayNode a ? a.size() : 0;
        int workingSize = readWorkingCollection(contract, working, paramPath) instanceof ArrayNode w ? w.size() : 0;
        int len = Math.max(argSize, workingSize);
        return IntStream.range(0, len).mapToObj(i -> paramPath.replace(RuntimeUtils.WILDCARD_ARRAY_INDEX, "[" + i + "]")).toList();
    }

    /**
     * 数组长度一致性：入参与 Material 集合同时非空且长度不等则拒。
     *
     * @param wildcardPath 带 {@code []} 的参数路径
     * @param contract     参数契约
     * @param args         当前入参
     * @param working      当前节点取值树
     * @return 拒调观察；一致则空
     */
    private static Optional<Observation> checkArrayLength(String wildcardPath, ArgSpec contract, ObjectNode args, JsonNode working) {
        String container = arrayContainerPath(wildcardPath);
        if (Strings.isBlank(container)) {
            return Optional.empty();
        }

        int argSize = JSON.lookupNode(args, container) instanceof ArrayNode a ? a.size() : 0;
        int workingSize = readWorkingCollection(contract, working, wildcardPath) instanceof ArrayNode w ? w.size() : 0;
        // 两侧都有行时长度必须一致，否则拒调
        if (workingSize > 0 && argSize > 0 && workingSize != argSize) {
            return Optional.of(Observation.rejected(
                RuntimeErrorCode.PARAM_PROVENANCE_REJECTED,
                ModelHint.PARAM_ARRAY_LEN_MISMATCH.text(
                    "target", String.valueOf(argSize), "working", String.valueOf(workingSize)
                ),
                ModelHint.NEXT_ALIGN_ROWS.text()
            ));
        }

        return Optional.empty();
    }

    /**
     * 校验单条展开路径：缺则注入；仍缺走 missingAction；有值则做来源/枚举校验。
     *
     * @param path        已展开路径
     * @param contract    参数契约
     * @param args        当前入参
     * @param working     当前节点取值树
     * @param workingRows 工作树数组行；非数组为 {@code null}
     * @param rowField    数组项字段；非数组为 {@code null}
     * @param rowIndex    当前行下标
     * @param request     调用请求（写来源）
     * @param ctx         执行上下文
     * @param acc         累计器
     * @return 即时拒调观察；无则空
     */
    private Optional<Observation> validatePath(String path, ArgSpec contract, ObjectNode args, JsonNode working,
                                               @Nullable JsonNode workingRows, @Nullable String rowField, int rowIndex,
                                               ToolInvokeRequest request, ToolExecutionContext ctx, GateAccumulator acc) {

        // 绑定只由 ArgBinding 负责；此处只校验模型/绑定后的入参
        JsonNode argVal = JSON.lookupNode(args, path);
        if (!JSON.hasValue(argVal) && workingRows instanceof ArrayNode arr && rowIndex < arr.size()
            && Strings.isNotBlank(rowField)) {
            JsonNode row = arr.get(rowIndex);
            if (row instanceof ObjectNode obj) {
                JsonNode rowVal = JSON.lookupNode(obj, rowField);
                if (JSON.hasValue(rowVal)) {
                    argVal = rowVal;
                }
            }
        }

        final JsonNode resolved = argVal;
        String askLeaf = Objects.nonNull(rowField) ? rowField : RuntimeUtils.leafKey(path);
        if (!JSON.hasValue(resolved)) {
            // 选填缺失：直接跳过，missing 只对必填参数生效
            if (!contract.isRequired()) {
                return Optional.empty();
            }
            return handleMissing(path, contract, askLeaf, ctx, acc);
        }

        SourceType trusted = sourceResolver.resolve(contract, path, resolved, working, request, ctx);
        if (contract.isForbidSynthetic() && trusted == SourceType.MODEL) {
            log.debug("[ParamContract] synthetic path={} sources={}",
                path, contract.getSources()
            );
            // 允许 USER 时转追问，否则即时拒调
            if (contract.allows(SourceType.USER)) {
                acc.askUser.add(askLeaf);
                return Optional.empty();
            }

            return Optional.of(syntheticRejection(path, contract));
        }

        if (!contract.getEnumValues().isEmpty() && contract.getEnumValues().stream().noneMatch(v -> v.equals(JSON.normalizeNode(resolved)))) {
            acc.askUser.add(askLeaf);
        }

        return Optional.empty();
    }

    /**
     * 数组容器：只认 from（含通配容器路径）；无 from 不猜。
     *
     * @param contract  参数契约
     * @param working   当前节点取值树
     * @param paramPath 参数路径
     * @return 数组节点；没有为 {@code null}
     */
    private static @Nullable JsonNode readWorkingCollection(ArgSpec contract, JsonNode working, String paramPath) {
        ValueRef valueRef = contract.getValueRef();
        if (Objects.isNull(valueRef) || valueRef.bag() == ValueRef.Bag.EVIDENCE) {
            return null;
        }
        ValueRef container = valueRef;
        if (Strings.contains(valueRef.path(), RuntimeUtils.WILDCARD_ARRAY_INDEX)) {
            String containerPath = arrayContainerPath(valueRef.path());
            if (Strings.isNotBlank(containerPath)) {
                container = new ValueRef(valueRef.bag(), containerPath);
            }
        }
        JsonNode direct = WorkingTree.lookup(working, container);
        return direct instanceof ArrayNode ? direct : null;
    }

    /**
     * 缺参时按 missingAction 累计 FAIL / CALL_TOOL / ASK_USER；数组 CALL_TOOL 可即时拒调。
     *
     * @param path     参数路径
     * @param contract 参数契约
     * @param askLeaf  追问用叶子键
     * @param ctx      执行上下文
     * @param acc      累计器
     * @return 即时拒调观察；多数情况只累计后为空
     */
    private Optional<Observation> handleMissing(String path, ArgSpec contract, String askLeaf,
                                                ToolExecutionContext ctx, GateAccumulator acc) {

        MissingActionType action = missingAction(contract);
        return switch (action) {
            case FAIL -> {
                acc.failReason = Optional.ofNullable(missingMessage(contract))
                    .map(String::trim)
                    .filter(Strings::isNotBlank)
                    .orElse(ModelHint.PARAM_MISSING_TRUSTED.text("key", RuntimeUtils.leafKey(path)));
                yield Optional.empty();
            }

            case CALL_TOOL -> {
                String code = missingTool(contract);
                if (Strings.isBlank(code)) {
                    yield Optional.empty();
                }

                if (path.contains("[")) {
                    Observation rejected = Observation.rejected(
                        RuntimeErrorCode.PARAM_PROVENANCE_CALL_TOOL,
                        ModelHint.PARAM_COLLECTION_CALL_TOOL.text("path", path, "tool", code),
                        ModelHint.NEXT_CALL_TOOL.text("tool", code)
                    );
                    yield Optional.of(rejected);
                }

                acc.callTool = code;
                acc.callToolPath = path;
                yield Optional.empty();
            }

            case ASK_USER -> {
                acc.askUser.add(askLeaf);
                yield Optional.empty();
            }
        };
    }

    /**
     * forbidSynthetic 拒调观测：契约指明查询工具时下一步为先调该工具，否则追问用户或失败收口。
     *
     * @param paramPath 参数路径
     * @param contract  参数契约
     * @return 拒绝观测
     */
    private static Observation syntheticRejection(String paramPath, ArgSpec contract) {
        RuntimeErrorCode code = RuntimeErrorCode.PARAM_PROVENANCE_REJECTED;
        if (contract.allows(SourceType.EVIDENCE) && missingAction(contract) == MissingActionType.CALL_TOOL) {
            String tool = missingTool(contract);
            if (Strings.isNotBlank(tool)) {
                return Observation.rejected(code,
                    ModelHint.PARAM_NEED_EVIDENCE.text("path", paramPath, "tool", tool),
                    ModelHint.NEXT_CALL_TOOL.text("tool", tool)
                );
            }

            return Observation.rejected(code,
                ModelHint.PARAM_NEED_EVIDENCE_NO_TOOL.text("path", paramPath), ModelHint.NEXT_ASK_OR_FAIL.text()
            );
        }

        return Observation.rejected(code, ModelHint.PARAM_NO_SYNTHETIC.text("path", paramPath), ModelHint.NEXT_ASK_OR_FAIL.text());
    }

    /**
     * 缺参动作，默认追问。
     *
     * @param spec 参数契约
     * @return 缺参动作
     */
    private static MissingActionType missingAction(ArgSpec spec) {
        MissingSpec missing = spec.getMissing();
        return Objects.isNull(missing) ? MissingActionType.ASK_USER : missing.actionOrDefault();
    }

    /**
     * 缺参时要先调用的工具名。
     *
     * @param spec 参数契约
     * @return 工具名；未配置为 {@code null}
     */
    private static @Nullable String missingTool(ArgSpec spec) {
        MissingSpec missing = spec.getMissing();
        return Objects.isNull(missing) ? null : missing.getTool();
    }

    /**
     * 缺参失败文案。
     *
     * @param spec 参数契约
     * @return 文案；未配置为 {@code null}
     */
    private static @Nullable String missingMessage(ArgSpec spec) {
        MissingSpec missing = spec.getMissing();
        return Objects.isNull(missing) ? null : missing.getMessage();
    }

    /**
     * [] 通配前的容器路径；无通配返回 null
     *
     * @param path 参数路径；可空
     * @return 容器路径；无通配为 {@code null}
     */
    private static @Nullable String arrayContainerPath(@Nullable String path) {
        if (!Strings.contains(path, RuntimeUtils.WILDCARD_ARRAY_INDEX)) {
            return null;
        }

        return path.substring(0, path.indexOf(RuntimeUtils.WILDCARD_ARRAY_INDEX));
    }
}
