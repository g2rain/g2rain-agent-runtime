package com.g2rain.agent.runtime.spi;

import com.g2rain.agent.runtime.spec.SideEffect;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 一次已成功的工具调用（读或写，不论结果是否为空），供 {@link ToolGuard} 判断事实是否仍然有效。
 *
 * @param toolName   工具注册名
 * @param arguments  入参（来自证据头脱敏摘要）
 * @param sideEffect 副作用；{@link SideEffect#NONE} 为读
 * @param facts      本次调用结果的抽取原文
 * @author alpha
 * @since 2026/11/20
 */
public record Call(String toolName, JsonNode arguments, SideEffect sideEffect, List<Fact> facts) {

    public Call {
        facts = List.copyOf(facts);
    }
}
