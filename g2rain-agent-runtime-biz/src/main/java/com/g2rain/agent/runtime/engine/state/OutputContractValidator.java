package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.domain.hydrate.OutputSpec;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.SourceType;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 校验 builtin_output_set 的键是否为契约声明字段，以及来源是否被允许。
 *
 * @author alpha
 * @since 2026/10/3
 */
@Component
public class OutputContractValidator {

    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 校验输出键是否被当前契约声明。
     *
     * <p>契约没有声明 outputs 时保留兼容行为并跳过键白名单校验；一旦声明了 outputs，
     * 任意未声明键都会被拒绝。</p>
     *
     * @param contract 当前 Skill 契约；为空表示没有输出白名单
     * @param key      待写入的输出键
     * @throws BusinessException 输出键未被契约声明时抛出
     */
    public void requireDeclared(@Nullable SkillContract contract, String key) {
        if (Objects.isNull(contract) || Collections.isEmpty(contract.getOutputs())) {
            return;
        }
        if (!contract.getOutputs().containsKey(key)) {
            throw new BusinessException(RuntimeErrorCode.OUTPUT_WRITE_FORBIDDEN, key);
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
     * @throws BusinessException 来源类型或 CONTRACT 常量不符合契约时抛出
     */
    public void requireAcceptedSource(@Nullable SkillContract contract, String key, Source source,
                                      @Nullable Object value) {
        if (Objects.isNull(contract) || Collections.isEmpty(contract.getOutputs())) {
            return;
        }
        OutputSpec spec = contract.getOutputs().get(key);
        if (Objects.isNull(spec)) {
            throw new BusinessException(RuntimeErrorCode.OUTPUT_WRITE_FORBIDDEN, key);
        }
        SourceType type = source.type();
        if (!spec.allows(type)) {
            throw new BusinessException(RuntimeErrorCode.SOURCE_REJECT, key);
        }
        if (type == SourceType.CONTRACT) {
            JsonNode constant = spec.getConstant();
            if (Objects.isNull(constant) || !JSON.nodesEqual(constant, JSON.obj2node(value))) {
                throw new BusinessException(RuntimeErrorCode.SOURCE_REJECT, key);
            }
        }
    }
}
