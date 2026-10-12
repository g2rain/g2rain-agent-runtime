package com.g2rain.agent.runtime.domain.hydrate;


import com.g2rain.common.utils.Strings;

/**
 * 装载后为 ArgSpec 解析 {@link ValueRef}。
 *
 * <p>该类型负责把契约中的路径文本预先转换为运行时引用对象，避免工具执行期间重复解析
 * 参数来源，并在非法路径出现时尽早暴露配置错误。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class ArgSpecHydrate {

    /**
     * 静态装载工具，禁止实例化。
     */
    private ArgSpecHydrate() {

    }

    /**
     * 为契约中的参数来源建立运行期对象。
     *
     * @param contract 已装载的技能契约
     * @apiNote {@code ArgSpecHydrate.hydrate(skillContract)} 通常在发布校验通过后调用；
     * 之后参数绑定直接使用 {@link ValueRef}，无需重复解析路径文本。
     */
    public static void hydrate(SkillContract contract) {
        contract.getTools().values().forEach(tool ->
            tool.getArgs().values().forEach(ArgSpecHydrate::hydrateOne)
        );
    }

    /**
     * 解析单个参数的 {@code from} 表达式。
     *
     * @param arg 参数规格
     */
    private static void hydrateOne(ArgSpec arg) {
        String from = Strings.trim(arg.getFrom());
        // 空白 from 表示不走表达式取值（常量或其它绑定路径）
        if (Strings.isNotBlank(from)) {
            arg.setValueRef(ValueRef.parse(from));
        }
    }
}
