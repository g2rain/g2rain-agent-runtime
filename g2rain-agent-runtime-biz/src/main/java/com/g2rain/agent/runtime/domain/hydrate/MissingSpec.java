package com.g2rain.agent.runtime.domain.hydrate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * 缺参动作规格：必填参数绑不上时追问、调补全工具或失败。
 *
 * <p>挂在 {@link ArgSpec#getMissing()} 上；未配置动作时按 {@link MissingActionType#ASK_USER}。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Getter
@Setter
@NoArgsConstructor
public class MissingSpec {

    /**
     * 缺参动作；未配置时按 {@link MissingActionType#ASK_USER}。
     */
    private @Nullable MissingActionType action;

    /**
     * {@link MissingActionType#CALL_TOOL} 时要调用的工具注册名。
     */
    private @Nullable String tool;

    /**
     * 给人/模型看的说明；可空则用默认提示。
     */
    private @Nullable String message;

    /**
     * @return 缺省 ASK_USER
     */
    public MissingActionType actionOrDefault() {
        return Objects.requireNonNullElse(action, MissingActionType.ASK_USER);
    }
}
