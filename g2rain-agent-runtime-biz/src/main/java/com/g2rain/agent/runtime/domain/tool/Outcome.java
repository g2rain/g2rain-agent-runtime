package com.g2rain.agent.runtime.domain.tool;

/**
 * 工具观测的结论类型：由产生观测的一方判定，节点内循环只按类型计数。
 *
 * <p>推进与停滞是结论自身的属性：{@link #advances()} 清零连续无进展，{@link #stalls()} 记一次无进展；
 * 两者皆否的 {@link #DEFERRED}、{@link #UNAVAILABLE} 不计也不清零，由节点总轮次上限兜底。</p>
 *
 * @author alpha
 * @since 2026/10/11
 */
public enum Outcome {

    /**
     * 工具真正执行并成功。
     */
    SUCCESS(true, false),

    /**
     * 工具真正执行了，但调用层或业务层失败。
     */
    FAILED(false, true),

    /**
     * 调用违反规则，执行前被拒；模型遵守契约与工具规则即可避免。
     */
    REJECTED(false, true),

    /**
     * 调用本身正当，但须先完成前置步骤（如申请确认、等待写结果核实）。
     */
    DEFERRED(false, false),

    /**
     * 调用正当，但外部资源暂不可用（如网页拒绝读取、返回 403）；换一个资源即可，不算模型原地打转。
     */
    UNAVAILABLE(false, false);

    /**
     * 是否算作推进。
     */
    private final boolean advances;

    /**
     * 是否算作一次无进展。
     */
    private final boolean stalls;

    Outcome(boolean advances, boolean stalls) {
        this.advances = advances;
        this.stalls = stalls;
    }

    /**
     * 是否算作推进：清零连续无进展。
     *
     * @return 推进时返回 {@code true}
     */
    public boolean advances() {
        return advances;
    }

    /**
     * 是否算作一次无进展。
     *
     * @return 停滞时返回 {@code true}
     */
    public boolean stalls() {
        return stalls;
    }
}
