package com.g2rain.agent.runtime.engine.flow.react;


import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.common.utils.Collections;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * DefaultPhaseHint：默认节点执行提示。
 * <p>
 * 有节点产出 → 可收尾；尚无证据 → 查业务；否则 → 写 outputs。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Component
public class DefaultPhaseHint implements PhaseHint {

    /**
     * 按节点产出与证据情况返回当前处理方向提示。
     *
     * @param node 当前节点视图；可空
     * @return 提示文案；缺节点则为 null
     */
    @Override
    public @Nullable String hint(@Nullable NodeScope node) {
        if (Objects.isNull(node)) {
            return null;
        }

        if (!node.outputs().isEmpty()) {
            return "建议阶段：可收尾";
        }

        if (Collections.isEmpty(node.evidence())) {
            return "建议阶段：查业务";
        }

        return "建议阶段：写 outputs";
    }
}
