package com.g2rain.agent.runtime.spi;

import com.g2rain.agent.runtime.spec.ToolSpec;

import java.util.Map;

/**
 * {@link SpecSource} 返回的动作语义集合。
 *
 * @param tools 工具注册名 → 工具契约；整体替换契约自带的 {@code tools}
 * @param pin   本次所用版本的标记；由实现方定义，Runtime 原样保存到任务
 * @author alpha
 * @since 2026/11/17
 */
public record SpecSet(Map<String, ToolSpec> tools, String pin) {

    public SpecSet {
        tools = Map.copyOf(tools);
    }
}