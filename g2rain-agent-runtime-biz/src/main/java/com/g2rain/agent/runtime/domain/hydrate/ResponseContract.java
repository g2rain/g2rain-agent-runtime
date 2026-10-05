package com.g2rain.agent.runtime.domain.hydrate;


import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * ResponseSchemaContract（响应契约）：描述工具返回体怎样算业务成功、错误字段在哪。
 * <p>
 * 供工具响应契约门判定业务成败。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class ResponseContract {

    /**
     * 成功条件：路径 + 比较算子 + 期望值
     */
    private final @Nullable SuccessCondition successCondition;

    /**
     * 错误字段路径：码与文案分别从哪取
     */
    private final @Nullable ErrorPath errorPath;

    /**
     * 组装契约。
     *
     * @param successCondition 成功条件；可空
     * @param errorPath        错误路径；可空
     * @return 响应契约实例
     */
    public static ResponseContract of(@Nullable SuccessCondition successCondition, @Nullable ErrorPath errorPath) {
        return new ResponseContract(successCondition, errorPath);
    }

    /**
     * 组装契约字段；空条件表示不按路径判定业务成功。
     *
     * @param successCondition 成功条件；可空
     * @param errorPath        错误码与文案路径；可空
     */
    private ResponseContract(@Nullable SuccessCondition successCondition, @Nullable ErrorPath errorPath) {
        this.successCondition = successCondition;
        this.errorPath = errorPath;
    }

    /**
     * @return 成功条件；可空
     */
    public @Nullable SuccessCondition successCondition() {
        return successCondition;
    }

    /**
     * @return 错误路径；可空
     */
    public @Nullable ErrorPath errorPath() {
        return errorPath;
    }

    /**
     * SuccessCondition（成功条件）：对响应体某路径做算子判定。
     *
     * @param path  点分路径，如 {@code status}
     * @param op    比较算子（与技能契约 {@link SuccessOp} 同一套）
     * @param value 期望值（IN/NOT_IN 时常为集合）；可空
     */
    public record SuccessCondition(String path, SuccessOp op, @Nullable JsonNode value) {

    }

    /**
     * ErrorPath（错误路径）：失败时从哪取业务码与文案。
     *
     * @param codePath    错误码路径；可空
     * @param messagePath 错误文案路径；可空
     */
    public record ErrorPath(@Nullable String codePath, @Nullable String messagePath) {

    }
}
