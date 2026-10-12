package com.g2rain.agent.runtime.engine.flow.react;


import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * DefaultPhaseHint：按节点完成目标与契约必填产出给出提示。
 *
 * <p>有完成目标时说明它是否已成功调用；认领来的目标只是推测，成功后不说"完成目标已成功"，改为提示有未完成的写操作就继续；契约有必填产出时列出尚未写入的键，全部写入后提示已写齐。
 * 两者都有时用分号拼接。例：完成目标 {@code post_site_save} 尚未成功且必填 {@code site_id} 未写入时，提示
 * {@code 完成目标 post_site_save 尚未成功调用；必填 outputs 尚未写入：site_id}。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Component
public class DefaultPhaseHint implements PhaseHint {

    /**
     * 提示分隔符。
     */
    private static final String SEPARATOR = "；";

    /**
     * 按完成目标、契约必填产出与节点现状给出提示。
     *
     * @param node     当前节点视图；可空
     * @param contract 当前技能契约；可空
     * @param target   节点完成目标；可空
     * @param claimed  完成目标由首次写调用认领
     * @return 提示文案；无节点，或既无完成目标又无必填产出时为 null
     */
    @Override
    public @Nullable String hint(@Nullable NodeScope node, @Nullable SkillContract contract, @Nullable String target,
                                 boolean claimed) {
        if (Objects.isNull(node)) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        if (Strings.isNotBlank(target)) {
            boolean done = node.evidence().stream().anyMatch(head -> target.equals(head.toolName()) && head.done());
            parts.add(phaseOf(done, claimed).text("tool", target));
        }

        String outputs = outputsHint(node, contract);
        if (Objects.nonNull(outputs)) {
            parts.add(outputs);
        }

        return parts.isEmpty() ? null : String.join(SEPARATOR, parts);
    }

    /**
     * 完成目标的阶段文案：未成功提示待调用；成功时声明的目标说已成功，认领的目标只说已完成该写操作。
     *
     * @param done    目标写调用已成功
     * @param claimed 目标由首次写调用认领
     * @return 阶段文案
     */
    private static ModelHint phaseOf(boolean done, boolean claimed) {
        if (!done) {
            return ModelHint.PHASE_TARGET_PENDING;
        }

        return claimed ? ModelHint.PHASE_WRITE_DONE : ModelHint.PHASE_TARGET_DONE;
    }

    /**
     * 必填产出的写入情况。
     *
     * @param node     当前节点视图
     * @param contract 当前技能契约；可空
     * @return 提示文案；契约没有必填产出时为 null
     */
    private static @Nullable String outputsHint(NodeScope node, @Nullable SkillContract contract) {
        if (Objects.isNull(contract) || Collections.isEmpty(contract.getOutputs())) {
            return null;
        }

        List<String> required = contract.getOutputs().entrySet().stream()
            .filter(entry -> entry.getValue().isRequired())
            .map(Map.Entry::getKey)
            .toList();
        if (required.isEmpty()) {
            return null;
        }

        List<String> missing = required.stream().filter(key -> !node.outputs().containsKey(key)).toList();
        if (missing.isEmpty()) {
            return ModelHint.PHASE_OUTPUTS_DONE.text();
        }

        return ModelHint.PHASE_OUTPUTS_MISSING.text("keys", String.join("、", missing));
    }
}
