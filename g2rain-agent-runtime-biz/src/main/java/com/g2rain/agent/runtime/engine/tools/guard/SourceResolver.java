package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.domain.hydrate.ArgSpec;
import com.g2rain.agent.runtime.domain.hydrate.ValueRef;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.SourceType;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Objects;


/**
 * 入参可信来源判定：先认绑定来源，再按契约声明的来源逐一精确比对；都不符视为 MODEL。
 *
 * @author alpha
 * @since 2026/10/12
 */
@Component
@RequiredArgsConstructor
public class SourceResolver {

    /**
     * JSON 编解码
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 来源精确校验。
     */
    private final SourceVerifier sourceVerifier;

    /**
     * 判定入参当前值最可信来源；无一匹配则视为 MODEL。
     *
     * @param contract 参数契约
     * @param path     参数路径
     * @param argVal   入参值
     * @param working  当前节点取值树
     * @param request  调用请求
     * @param ctx      执行上下文
     * @return 可信来源类型
     */
    public SourceType resolve(ArgSpec contract, String path, JsonNode argVal, JsonNode working,
                               ToolInvokeRequest request, ToolExecutionContext ctx) {
        Source bound = request.argSources().get(path);
        boolean declared = Objects.nonNull(contract.getValueRef()) || Objects.nonNull(contract.getConstant());
        if (declared) {
            if (Objects.nonNull(bound) && sourceVerifier.verify(bound, argVal, ctx)) {
                return bound.type();
            }
            return SourceType.MODEL;
        }

        if (Objects.nonNull(bound) && contract.getSources().contains(bound.type())
            && sourceVerifier.verify(bound, argVal, ctx)) {
            return bound.type();
        }

        return contract.getSources().stream()
            .filter(source -> matchesSource(source, contract, path, argVal, working, ctx))
            .findFirst().orElse(SourceType.MODEL);
    }

    /**
     * 入参值是否与指定来源一致（精确比对）。
     *
     * @param source   待比对来源类型
     * @param contract 参数契约
     * @param path     参数路径
     * @param argVal   入参值
     * @param working  当前节点取值树
     * @param ctx      执行上下文
     * @return 与该来源一致
     */
    private boolean matchesSource(SourceType source, ArgSpec contract, String path, JsonNode argVal, JsonNode working,
                                  ToolExecutionContext ctx) {
        return switch (source) {
            case PLATFORM -> matchesPlatform(contract, argVal, ctx);
            case CONTRACT -> Objects.nonNull(contract.getConstant()) && JSON.nodesEqual(contract.getConstant(), argVal);
            // 无 from 时模型填的前情值无法按参数名定位，改按实体 id 认定
            case PRIOR_TASK -> Objects.isNull(contract.getValueRef())
                ? sourceVerifier.matchesPriorId(ctx, argVal)
                : matchesWorking(contract, working, path, source, argVal);
            case NODE_OUTPUT, SHARED, SESSION -> matchesWorking(contract, working, path, source, argVal);
            case USER -> sourceVerifier.followUpAccepted(ctx, path) || sourceVerifier.findUserMessageId(ctx, argVal).isPresent();
            case EVIDENCE -> sourceVerifier.matchesEvidenceValue(ctx, argVal);
            case MODEL -> false;
        };
    }

    /**
     * 工作树中按来源读到的值是否与入参相等。
     *
     * @param contract 参数契约
     * @param working  当前节点取值树
     * @param path     参数路径
     * @param source   来源类型
     * @param argVal   入参值
     * @return 相等
     */
    private static boolean matchesWorking(ArgSpec contract, JsonNode working, String path, SourceType source, JsonNode argVal) {
        JsonNode workingVal = readWorkingValue(contract, working, path, source);
        return JSON.hasValue(workingVal) && JSON.nodesEqual(workingVal, argVal);
    }

    /**
     * 有 from 只认 from；无 from 按来源类型读对应袋（不跨袋猜叶子）。
     *
     * @param contract  参数契约
     * @param working   当前节点取值树
     * @param paramPath 参数路径
     * @param source    来源类型
     * @return 工作树中的值；没有为 {@code null}
     */
    private static @Nullable JsonNode readWorkingValue(ArgSpec contract, JsonNode working, String paramPath, SourceType source) {
        ValueRef valueRef = contract.getValueRef();
        if (Objects.nonNull(valueRef) && valueRef.bag() != ValueRef.Bag.EVIDENCE) {
            return WorkingTree.lookup(working, valueRef);
        }

        ValueRef.Bag bag = switch (source) {
            case NODE_OUTPUT -> ValueRef.Bag.OUTPUTS;
            case SHARED -> ValueRef.Bag.SHARED;
            case SESSION -> ValueRef.Bag.SESSION;
            default -> null;
        };

        if (Objects.isNull(bag) || Strings.isBlank(paramPath)) {
            return null;
        }

        return WorkingTree.lookup(working, new ValueRef(bag, paramPath));
    }

    /**
     * 入参是否等于本轮 Platform 快照（须契约显式 from: platform.*）。
     *
     * @param contract 参数契约
     * @param argVal   入参值
     * @param ctx      执行上下文
     * @return 平台字段匹配
     */
    private static boolean matchesPlatform(ArgSpec contract, JsonNode argVal, ToolExecutionContext ctx) {
        ValueRef ref = contract.getValueRef();
        if (Objects.isNull(ref) || ref.bag() != ValueRef.Bag.PLATFORM || Strings.isBlank(ref.path())) {
            return false;
        }

        return ctx.getFrame().turn().platform().matches(ref.path(), argVal);
    }
}
