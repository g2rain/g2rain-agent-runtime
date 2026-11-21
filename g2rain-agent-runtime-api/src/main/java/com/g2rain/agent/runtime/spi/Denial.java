package com.g2rain.agent.runtime.spi;

/**
 * {@link ToolGuard} 的拒绝结果。
 *
 * <p>{@code code} 是实现方的规则编码；{@code message} 与 {@code nextStep} 进入观测文案
 * 「原因。下一步：…」。{@code lookup} 为 {@code true} 表示调用本身没有被否决，只是判断所需的数据还不全，
 * 补查后重新调用即可：执行过程中不显示为失败，也不作为需要用户决定的拒绝。</p>
 *
 * <pre>{@code
 * Denial.refuse("article-published-undeletable", "已发布的文章不能删除", "向用户说明原因");
 * Denial.lookup("FACT_MISSING", "需先补查最新数据", "先调用工具 [x] 查询，再重新调用本工具");
 * }</pre>
 *
 * @param code     实现方规则编码
 * @param message  拒绝原因（给模型）
 * @param nextStep 模型下一步
 * @param lookup   是否只是需要先补查数据
 * @author alpha
 * @since 2026/11/17
 */
public record Denial(String code, String message, String nextStep, boolean lookup) {

    /**
     * 业务拒绝：当前状态下不允许该调用。
     *
     * @param code     规则编码
     * @param message  拒绝原因
     * @param nextStep 模型下一步
     * @return 拒绝结果
     */
    public static Denial refuse(String code, String message, String nextStep) {
        return new Denial(code, message, nextStep, false);
    }

    /**
     * 需先补查：判断所需数据不全，补查后重新调用。
     *
     * @param code     规则编码
     * @param message  原因
     * @param nextStep 补查指引
     * @return 拒绝结果
     */
    public static Denial lookup(String code, String message, String nextStep) {
        return new Denial(code, message, nextStep, true);
    }
}
