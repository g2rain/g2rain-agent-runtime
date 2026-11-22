package com.g2rain.agent.runtime.spi;

/**
 * 动作语义来源：按契约根属性 {@code source} 的类型提供各工具的 {@code tools} 定义。
 *
 * <p>{@code source.type} 必填，取值见 {@link SpecSourceType}：{@link SpecSourceType#CONTRACT} 由 Runtime 处理；
 * 其余类型由声明该类型的实现提供。没有实现时只支持 {@link SpecSourceType#CONTRACT}。</p>
 *
 * <p>每个类型只能有一个实现，重复注册时 Runtime 启动失败。</p>
 *
 * @author alpha
 * @since 2026/11/17
 */
public interface SpecSource {

    /**
     * 本实现负责的来源类型，与契约 {@code source.type} 对应。
     *
     * @return 来源类型，不能是 {@link SpecSourceType#CONTRACT}
     */
    SpecSourceType type();

    /**
     * 提供该技能各工具的动作语义。
     *
     * <p>依赖暂时不可用时抛 {@link SpecUnavailableException}，本轮失败，任务保留，稍后可重试。
     * 其他异常表示该技能的来源配置有误，该技能按不可用处理（丢弃），不退回契约自带的 {@code tools}。</p>
     *
     * @param query 技能键、工具名、来源声明与已锁定版本
     * @return 工具语义与本次版本标记
     */
    SpecSet load(SpecQuery query);
}
