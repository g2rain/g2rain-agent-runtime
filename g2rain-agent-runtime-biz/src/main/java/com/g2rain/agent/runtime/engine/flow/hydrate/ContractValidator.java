package com.g2rain.agent.runtime.engine.flow.hydrate;


import com.g2rain.agent.runtime.domain.hydrate.ArgSpec;
import com.g2rain.agent.runtime.domain.hydrate.ExtractSpec;
import com.g2rain.agent.runtime.domain.hydrate.OutputSpec;
import com.g2rain.agent.runtime.domain.hydrate.OutputType;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.hydrate.ValueRef;
import com.g2rain.agent.runtime.domain.hydrate.VerifySpec;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.agent.runtime.support.PlatformCatalog;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * 发布/装载期技能契约校验。任一规则失败则该 Skill 不可用。
 *
 * <p>校验可委派技能不得含 WRITE 工具、写工具须有 success、confirm 仅允许 WRITE、
 * returnDirect 仅允许 NONE、入参 from 与常量互斥、平台引用须在目录内、LIST 产出须有 fields、默认完成目标 target 须是本技能的写工具。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContractValidator {

    /**
     * 平台字段目录，用于校验 {@code platform.*} 引用。
     */
    private final PlatformCatalog platformCatalog;

    /**
     * 判断契约是否可装载。
     *
     * @param contract 技能契约；{@code null} 视为不可用
     * @return {@code true} 表示可通过装载
     */
    public boolean valid(SkillContract contract) {
        if (contract.isDelegatable()) {
            for (ToolSpec tool : contract.getTools().values()) {
                if (tool.sideEffectOrDefault() == SideEffect.WRITE) {
                    // 可委派技能不能带写副作用，避免子图误写
                    return false;
                }
            }
        }

        for (Map.Entry<String, ToolSpec> entry : contract.getTools().entrySet()) {
            if (Strings.isBlank(entry.getKey())) {
                return false;
            }

            ToolSpec tool = entry.getValue();
            if (tool.sideEffectOrDefault() == SideEffect.WRITE && Objects.isNull(tool.getSuccess())) {
                return false;
            }

            if (Boolean.TRUE.equals(tool.getConfirm()) && tool.sideEffectOrDefault() != SideEffect.WRITE) {
                return false;
            }

            // 直接作答会跳过模型收口，只允许只读工具
            if (Strings.isNotBlank(tool.getReturnDirect()) && tool.sideEffectOrDefault() != SideEffect.NONE) {
                return false;
            }

            if (Objects.nonNull(tool.getVerify()) && !validVerify(contract, tool.getVerify())) {
                return false;
            }

            for (Map.Entry<String, ArgSpec> argEntry : tool.getArgs().entrySet()) {
                ArgSpec arg = argEntry.getValue();
                if (Strings.isNotBlank(arg.getFrom()) && Objects.nonNull(arg.getConstant())) {
                    return false;
                }

                if (Strings.isNotBlank(arg.getFrom()) && !ValueRef.isKnown(arg.getFrom())) {
                    return false;
                }

                ValueRef ref = arg.getValueRef();
                if (Objects.isNull(ref) && Strings.isNotBlank(arg.getFrom()) && ValueRef.isKnown(arg.getFrom())) {
                    ref = ValueRef.parse(arg.getFrom());
                }
                if (Objects.nonNull(ref) && ref.bag() == ValueRef.Bag.PLATFORM
                    && !platformCatalog.supports(ref.path())) {
                    return false;
                }

                warnIfPlatformNameWithoutFrom(entry.getKey(), argEntry.getKey(), arg);
            }

            for (ExtractSpec extract : tool.getExtracts()) {
                if (Strings.isBlank(extract.getPath())) {
                    return false;
                }
            }
        }

        for (OutputSpec output : contract.getOutputs().values()) {
            if (output.typeOrDefault() == OutputType.LIST && Collections.isEmpty(output.getFields())) {
                return false;
            }
        }

        // 默认完成目标须是本技能声明的写工具
        return Strings.isBlank(contract.getTarget()) || contract.writes(contract.getTarget());
    }

    /**
     * 参数名像平台键但未声明 {@code from: platform.*} 时打警告，不判失败。
     *
     * @param toolName 工具名
     * @param argPath  参数路径
     * @param arg      参数契约
     */
    private void warnIfPlatformNameWithoutFrom(String toolName, String argPath, ArgSpec arg) {
        if (Objects.nonNull(arg.getValueRef()) && arg.getValueRef().bag() == ValueRef.Bag.PLATFORM) {
            return;
        }

        if (Strings.isNotBlank(arg.getFrom())) {
            return;
        }

        String leaf = RuntimeUtils.leafKey(argPath);
        if (platformCatalog.supports(leaf)) {
            log.warn("[ContractValidator] arg {} on tool {} shares platform key {} but has no from: platform.*",
                argPath, toolName, leaf);
        }
    }

    /**
     * 回查工具必须存在且副作用为 NONE。
     *
     * @param contract 技能契约
     * @param verify   回查声明
     * @return 工具存在且为读副作用时为 {@code true}
     */
    private static boolean validVerify(SkillContract contract, VerifySpec verify) {
        if (Strings.isBlank(verify.getTool())) {
            return false;
        }

        ToolSpec target = contract.tool(verify.getTool());
        return Objects.nonNull(target) && target.sideEffectOrDefault() == SideEffect.NONE;
    }
}
