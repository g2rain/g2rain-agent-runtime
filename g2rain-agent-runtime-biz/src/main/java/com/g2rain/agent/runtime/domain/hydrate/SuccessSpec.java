package com.g2rain.agent.runtime.domain.hydrate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 工具业务成功条件：对响应体某路径做算子判定。
 *
 * <p>与 {@link ResponseContract.SuccessCondition} 同一套 {@link SuccessOp}；未命中则业务成败留空，
 * 由上层按副作用策略处理。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Getter
@Setter
@NoArgsConstructor
public class SuccessSpec {

    /**
     * 读取路径。
     */
    private String path;

    /**
     * 比较运算符。
     */
    private SuccessOp op;

    /**
     * 期望值。
     */
    private @Nullable JsonNode value;
}
