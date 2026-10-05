package com.g2rain.agent.runtime.engine.flow.finalize;


import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.common.utils.Strings;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * 无模型时按优先级拣选确定性答复草稿。
 *
 * <p>顺序：执行器文案 → 固定兜底。来源标记仅 {@code EXECUTOR} / {@code FALLBACK}。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
public final class FinalMessagePicker {

    /**
     * 禁止实例化：只提供静态拣选。
     */
    private FinalMessagePicker() {

    }

    /**
     * 拣选结果。
     *
     * @param text   文案
     * @param source 来源标记（EXECUTOR / FALLBACK）
     */
    public record Pick(String text, String source) {

    }

    /**
     * 按优先级拣选答复草稿。
     *
     * @param executorMessage 执行器带回的文案；可空
     * @return 文案与来源
     */
    public static Pick pick(@Nullable String executorMessage) {
        if (Strings.isNotBlank(executorMessage)) {
            Pick pick = new Pick(executorMessage.trim(), "EXECUTOR");
            log.info("[FinalMessagePicker] pick source={} text={}", pick.source(), pick.text());
            return pick;
        }
        Pick pick = new Pick(RuntimeMessage.REPLY_DONE.text(), "FALLBACK");
        log.info("[FinalMessagePicker] pick source={} text={}", pick.source(), pick.text());
        return pick;
    }
}

