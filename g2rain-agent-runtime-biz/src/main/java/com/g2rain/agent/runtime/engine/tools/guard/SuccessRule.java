package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.domain.hydrate.SuccessOp;
import com.g2rain.agent.runtime.domain.hydrate.SuccessSpec;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 契约 success 判定；证据结算与响应契约嗅探共用。
 *
 * @author alpha
 * @since 2026/10/4
 */
public final class SuccessRule {

    /**
     * JSON 编解码。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 静态判定工具，禁止实例化。
     */
    private SuccessRule() {

    }

    /**
     * 契约是否配置了 success.path。
     *
     * @param contract 工具契约；可空
     * @return 配了可判定条件为 {@code true}
     */
    public static boolean configured(@Nullable ToolSpec contract) {
        return Objects.nonNull(conditionOf(contract));
    }

    /**
     * 按契约判定业务成败。
     *
     * <p>未配置 success 返回 {@code null}；配置了但响应体空为 {@code false}。</p>
     *
     * @param contract 工具契约；可空
     * @param body     响应体；可空
     * @return 业务成功/失败；未配置为 {@code null}
     */
    public static @Nullable Boolean businessOk(@Nullable ToolSpec contract, @Nullable JsonNode body) {
        Condition condition = conditionOf(contract);
        if (Objects.isNull(condition)) {
            return null;
        }

        if (!JSON.hasValue(body) || body.isEmpty()) {
            return Boolean.FALSE;
        }

        return evalSuccess(body, condition);
    }

    /**
     * 从工具契约构造成功条件。
     *
     * @param contract 工具契约；可空
     * @return 成功条件；未配 path 为 {@code null}
     */
    public static @Nullable Condition conditionOf(@Nullable ToolSpec contract) {
        if (Objects.isNull(contract)) {
            return null;
        }

        SuccessSpec success = contract.getSuccess();
        if (Objects.isNull(success) || Strings.isBlank(success.getPath())) {
            return null;
        }

        return new Condition(
            success.getPath().trim(),
            success.getOp(),
            success.getValue()
        );
    }

    /**
     * 按成功条件判断响应体。
     *
     * @param body      响应体
     * @param condition 契约成功条件
     * @return 条件成立为 {@code true}
     */
    public static boolean evalSuccess(JsonNode body, Condition condition) {
        JsonNode actual = JSON.lookupNode(body, condition.path());
        // 按算子比较；IN / NOT_IN 的期望值须为数组，非数组时 IN 不成立、NOT_IN 成立
        switch (condition.op()) {
            case EQ -> {
                return looseEquals(actual, condition.value());
            }
            case NE -> {
                return !looseEquals(actual, condition.value());
            }
            case IN -> {
                JsonNode expected = condition.value();
                if (Objects.isNull(expected) || !expected.isArray()) {
                    return false;
                }
                for (JsonNode v : expected) {
                    if (looseEquals(actual, v)) {
                        return true;
                    }
                }
                return false;
            }
            case NOT_IN -> {
                JsonNode expected = condition.value();
                if (Objects.isNull(expected) || !expected.isArray()) {
                    return true;
                }
                for (JsonNode v : expected) {
                    if (looseEquals(actual, v)) {
                        return false;
                    }
                }
                return true;
            }
            case TRUTHY -> {
                return isTruthy(actual);
            }
            case EXISTS -> {
                return Objects.nonNull(actual);
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * 宽松相等：节点相等，或两边都能转成数值且数值相等，或文本相等。
     *
     * @param actual   实际值；可空
     * @param expected 期望值；可空
     * @return 视为相等时为 {@code true}
     */
    static boolean looseEquals(@Nullable JsonNode actual, @Nullable JsonNode expected) {
        if (Objects.equals(actual, expected)) {
            return true;
        }

        if (Objects.isNull(actual) || Objects.isNull(expected)) {
            return false;
        }

        BigDecimal left = toDecimal(actual);
        BigDecimal right = toDecimal(expected);
        if (Objects.nonNull(left) && Objects.nonNull(right)) {
            return left.compareTo(right) == 0;
        }

        return actual.asString().equals(expected.asString());
    }

    /**
     * 把 JSON 节点转成十进制数。
     *
     * @param node JSON 节点
     * @return 数值；非数字且无法解析为 {@code null}
     */
    private static @Nullable BigDecimal toDecimal(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }

        if (node.isString()) {
            try {
                return new BigDecimal(node.asString().trim());
            } catch (NumberFormatException _) {
                return null;
            }
        }

        return null;
    }

    /**
     * 判断值是否为真：布尔取本值，数字非零，字符串非空且不为 {@code 0} / {@code false}，容器非空，其余标量为真。
     *
     * @param node JSON 节点
     * @return 为真时为 {@code true}
     */
    static boolean isTruthy(JsonNode node) {
        if (!JSON.hasValue(node)) {
            return false;
        }

        if (node.isBoolean()) {
            return node.booleanValue();
        }

        if (node.isNumber()) {
            return node.decimalValue().compareTo(BigDecimal.ZERO) != 0;
        }

        if (node.isString()) {
            String s = node.asString();
            if (s.isEmpty()) {
                return false;
            }

            return !"0".equals(s) && !"false".equalsIgnoreCase(s);
        }

        if (node.isContainer()) {
            return !node.isEmpty();
        }

        return true;
    }

    /**
     * Condition（成功条件）：对响应体某路径做算子判定。
     *
     * @param path  点分路径，如 {@code status}
     * @param op    比较算子（与技能契约 {@link SuccessOp} 同一套）
     * @param value 期望值（IN/NOT_IN 时常为集合）；可空
     */
    public record Condition(String path, SuccessOp op, @Nullable JsonNode value) {

    }
}
