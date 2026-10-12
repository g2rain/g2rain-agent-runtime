package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.domain.hydrate.OutputSpec;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.SourceType;
import com.g2rain.agent.runtime.engine.tools.ToolRejectedException;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 校验 builtin_output_set 的键是否为契约声明字段，以及来源是否被允许。
 *
 * <p>校验不通过时抛出 {@link ToolRejectedException}，下一步写明可用的键或来源类型；
 * 产出非必填时另提示可以不写。例：契约只声明 {@code web_site_list}（非必填）时写 {@code site}，
 * 下一步为 {@code key 只能取契约声明的产出：web_site_list；该产出非必填…}。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Component
public class OutputContractValidator {

    /**
     * JSON 编解码。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 校验输出键是否被当前契约声明。
     *
     * <p>契约没有声明 outputs 时跳过键白名单校验；一旦声明了 outputs，任意未声明键都会被拒绝。</p>
     *
     * @param contract 当前 Skill 契约；为空表示没有输出白名单
     * @param key      待写入的输出键
     * @throws ToolRejectedException 输出键未被契约声明时抛出
     */
    public void requireDeclared(@Nullable SkillContract contract, String key) {
        if (Objects.isNull(contract) || Collections.isEmpty(contract.getOutputs())) {
            return;
        }
        if (!contract.getOutputs().containsKey(key)) {
            throw new ToolRejectedException(RuntimeErrorCode.OUTPUT_WRITE_FORBIDDEN, keysHint(contract), key);
        }
    }

    /**
     * 校验输出来源类型，并在来源为 {@link SourceType#CONTRACT} 时校验常量值。
     *
     * <p>来源类型和输出字段均由契约决定。对于 CONTRACT 来源，值必须与契约常量做
     * JSON 结构比较，而不是依赖 Java 对象的 {@code equals} 实现。</p>
     *
     * @param contract 当前 Skill 契约；为空或没有 outputs 时跳过
     * @param key      待写入的输出键
     * @param source   输出值的来源描述
     * @param value    待写入的实际值
     * @throws ToolRejectedException 来源类型或 CONTRACT 常量不符合契约时抛出
     */
    public void requireAcceptedSource(@Nullable SkillContract contract, String key, Source source, @Nullable Object value) {
        if (Objects.isNull(contract) || Collections.isEmpty(contract.getOutputs())) {
            return;
        }
        OutputSpec spec = contract.getOutputs().get(key);
        if (Objects.isNull(spec)) {
            throw new ToolRejectedException(RuntimeErrorCode.OUTPUT_WRITE_FORBIDDEN, keysHint(contract), key);
        }
        SourceType type = source.type();
        if (!spec.allows(type)) {
            throw new ToolRejectedException(RuntimeErrorCode.SOURCE_REJECT,
                withOptional(contract, key, ModelHint.NEXT_OUTPUT_SOURCE_TYPE.text("types", allowedTypes(spec))),
                "outputs." + key + " 不接受 " + type + " 来源"
            );
        }
        if (type == SourceType.CONTRACT) {
            JsonNode constant = spec.getConstant();
            if (Objects.isNull(constant) || !JSON.nodesEqual(constant, JSON.obj2node(value))) {
                throw new ToolRejectedException(RuntimeErrorCode.SOURCE_REJECT,
                    withOptional(contract, key, ModelHint.NEXT_OUTPUT_CONST.text()),
                    "outputs." + key + " 与契约常量不一致"
                );
            }
        }
    }

    /**
     * 产出非必填时，在下一步后补一句"可以不写，直接结束"。
     *
     * <p>例：{@code withOptional(contract, "web_site_list", "sourceRef 填来源对象 ID")} 在该产出非必填时得到
     * {@code sourceRef 填来源对象 ID；该产出非必填，也可以不写，直接调用 builtin_node_finish}。</p>
     *
     * @param contract 当前 Skill 契约；可空
     * @param key      输出键
     * @param next     原下一步
     * @return 补充后的下一步
     */
    public String withOptional(@Nullable SkillContract contract, String key, String next) {
        if (Objects.isNull(contract) || Collections.isEmpty(contract.getOutputs())) {
            return next;
        }

        OutputSpec spec = contract.getOutputs().get(key);
        if (Objects.isNull(spec) || spec.isRequired()) {
            return next;
        }

        return next + "；" + ModelHint.NEXT_OUTPUT_OPTIONAL.text();
    }

    /**
     * 列出契约声明的产出键作为下一步。
     *
     * @param contract 当前 Skill 契约；可空
     * @return 下一步文案
     */
    public String keysHint(@Nullable SkillContract contract) {
        String keys = Objects.isNull(contract) || Collections.isEmpty(contract.getOutputs())
            ? "" : String.join("、", contract.getOutputs().keySet());
        return ModelHint.NEXT_OUTPUT_KEY.text("keys", keys);
    }

    /**
     * 该产出允许的来源类型，用顿号连接。
     *
     * @param spec 产出规格
     * @return 允许的来源类型
     */
    private static String allowedTypes(OutputSpec spec) {
        return Arrays.stream(SourceType.values()).filter(spec::allows).map(Enum::name).collect(Collectors.joining("、"));
    }
}
