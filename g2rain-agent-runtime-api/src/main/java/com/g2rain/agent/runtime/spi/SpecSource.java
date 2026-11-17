package com.g2rain.agent.runtime.spi;

import tools.jackson.databind.JsonNode;

/**
 * 动作语义来源：按契约根属性 {@code source} 提供各工具的 {@code tools} 定义。
 *
 * <p>Runtime 不解释 {@code source} 内容；由实现方决定能否提供、提供哪一版。</p>
 *
 * <p>多个实现时按实现类上的 {@code @Order}（或实现 {@code Ordered}）决定先后，
 * Runtime 按此顺序取第一个 {@link #supports} 为真的实现；未标注的排在最后。注入方不做额外排序。</p>
 *
 * @author alpha
 * @since 2026/11/17
 */
public interface SpecSource {

    /**
     * 能否为这份来源声明提供动作语义。
     *
     * @param source 契约根属性 {@code source} 的原文；Runtime 不解释
     * @return 能提供时为 {@code true}
     */
    boolean supports(JsonNode source);

    /**
     * 提供该技能各工具的动作语义。
     *
     * <p>无法提供时抛出异常，该技能按不可用处理；不退回契约自带的 {@code tools}。</p>
     *
     * @param query 技能键、工具名、来源声明与已锁定版本
     * @return 工具语义与本次版本标记
     */
    SpecSet load(SpecQuery query);
}
