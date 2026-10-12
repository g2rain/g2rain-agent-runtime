package com.g2rain.agent.runtime.domain.tool;


import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 一次工具调用后的观测结果。
 *
 * <p>观测由产生方（工具调用、拦截器、内置工具）判定结论类型，并带上给模型的说明、业务载荷和错误码。
 * 非成功观测必须同时给出原因与下一步，{@link #message()} 统一拼成"原因。下一步：…"，模型据此决定后续动作。例如：
 * {@code Observation.success("查到订单", orderJson)}、
 * {@code Observation.rejected(RuntimeErrorCode.ARG_REJECTED, "缺少必填参数 [buyer]", "补齐后再调用")}，
 * 后者的说明为 {@code 缺少必填参数 [buyer]。下一步：补齐后再调用}。</p>
 *
 * @param outcome   结论类型
 * @param text      成功摘要，或非成功的原因
 * @param next      模型下一步；成功时为 {@code null}，非成功时必填
 * @param data      业务载荷；执行前被拦下时为 {@code null}
 * @param errorCode 错误码；成功时为 {@code null}，非成功时必填
 * @author alpha
 * @since 2026/8/21
 */
public record Observation(Outcome outcome, String text, @Nullable String next, @Nullable JsonNode data,
                          @Nullable RuntimeErrorCode errorCode) {

    /**
     * 原因与下一步之间的连接语。
     */
    private static final String NEXT = "。下一步：";

    /**
     * 原因末尾需去掉的标点。
     */
    private static final String TRAILING = "。.；;，,";

    /**
     * 非成功观测必须带错误码与下一步。
     */
    public Observation {
        if (outcome != Outcome.SUCCESS && (Objects.isNull(errorCode) || Strings.isBlank(next))) {
            throw new IllegalArgumentException("non-success observation requires errorCode and next step");
        }
    }

    /**
     * 工具执行成功。
     *
     * @param message 成功说明
     * @param data    业务载荷；可空
     * @return 成功观测
     */
    public static Observation success(String message, @Nullable JsonNode data) {
        return new Observation(Outcome.SUCCESS, message, null, data, null);
    }

    /**
     * 工具执行了但失败。
     *
     * @param errorCode 错误码
     * @param reason    失败原因
     * @param next      模型下一步
     * @return 失败观测
     */
    public static Observation failed(RuntimeErrorCode errorCode, String reason, String next) {
        return failed(errorCode, reason, next, null);
    }

    /**
     * 工具执行了但失败，保留响应体供证据与抽取使用。
     *
     * @param errorCode 错误码
     * @param reason    失败原因
     * @param next      模型下一步
     * @param data      响应体；可空
     * @return 失败观测
     */
    public static Observation failed(RuntimeErrorCode errorCode, String reason, String next, @Nullable JsonNode data) {
        return new Observation(Outcome.FAILED, reason, next, data, errorCode);
    }

    /**
     * 调用违反规则，执行前被拒。
     *
     * @param errorCode 错误码
     * @param reason    拒绝原因
     * @param next      模型下一步
     * @return 拒绝观测
     */
    public static Observation rejected(RuntimeErrorCode errorCode, String reason, String next) {
        return new Observation(Outcome.REJECTED, reason, next, null, errorCode);
    }

    /**
     * 调用正当，但须先完成前置步骤。
     *
     * @param errorCode 错误码
     * @param reason    推迟原因
     * @param next      须先完成的步骤
     * @return 推迟观测
     */
    public static Observation deferred(RuntimeErrorCode errorCode, String reason, String next) {
        return new Observation(Outcome.DEFERRED, reason, next, null, errorCode);
    }

    /**
     * 替换下一步，其余不变；用于外层按调用情形给出更具体的下一步。
     *
     * <p>例：已批准写操作执行失败时，把通用的"修正后再调用"换成"向用户说明并询问如何处理"。</p>
     *
     * @param next 新的下一步
     * @return 新观测
     */
    public Observation withNext(String next) {
        return new Observation(outcome, text, next, data, errorCode);
    }

    /**
     * 给模型的说明：成功时为摘要；非成功时为"原因。下一步：…"，原因末尾的标点去掉后再接下一步。
     *
     * <p>例：原因 {@code 缺少参数 [a]。}、下一步 {@code 补齐后再调用}，得到 {@code 缺少参数 [a]。下一步：补齐后再调用}。</p>
     *
     * @return 说明文案
     */
    public String message() {
        if (Strings.isBlank(next)) {
            return text;
        }

        String head = Strings.defaultIfBlank(text, "").strip();
        while (!head.isEmpty() && TRAILING.indexOf(head.charAt(head.length() - 1)) >= 0) {
            head = head.substring(0, head.length() - 1);
        }

        return head + NEXT + next.strip();
    }
}
