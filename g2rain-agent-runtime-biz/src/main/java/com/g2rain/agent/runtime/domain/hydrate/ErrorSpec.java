package com.g2rain.agent.runtime.domain.hydrate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * 失败码与失败文案在响应体中的路径。
 *
 * <p>业务失败时按这些路径取值写入证据 {@code errorCode} 与摘要；可空表示不抽取对应字段。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Getter
@Setter
@NoArgsConstructor
public class ErrorSpec {

    /**
     * 错误码路径。
     */
    private @Nullable String code;

    /**
     * 错误文案路径。
     */
    private @Nullable String message;
}
