package com.g2rain.agent.runtime.spi;

import com.g2rain.agent.runtime.spec.SourceType;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 交给 {@link ToolGuard} 的只读调用视图。
 *
 * <p>{@code arguments} 是绑定与来源判定之后的最终参数（副本）；
 * {@code sources} 是各参数路径被判定的来源；
 * {@code facts} 是本节点及上游节点成功证据的抽取内容；
 * {@code pin} 是该技能锁定的版本标记；
 * {@code callId} 是本次调用标识；
 * {@code lookups} 是本节点与上游已成功的读工具调用；
 * {@code operator} 是当前请求用户标识。</p>
 *
 * @param skillKey  当前技能键
 * @param toolName  工具注册名
 * @param arguments 最终参数副本
 * @param sources   参数路径 → 来源类型
 * @param facts     本节点与上游的成功证据抽取
 * @param pin       技能锁定的版本标记；可空
 * @param callId    本次调用标识
 * @param lookups   本节点与上游已成功的读工具调用
 * @param operator  当前用户标识；访客为空
 * @author alpha
 * @since 2026/11/17
 */
public record CallView(String skillKey, String toolName, JsonNode arguments,
                       Map<String, SourceType> sources, List<Fact> facts, @Nullable String pin,
                       String callId, List<Lookup> lookups, @Nullable String operator) {

    public CallView {
        sources = Map.copyOf(sources);
        facts = List.copyOf(facts);
        lookups = List.copyOf(lookups);
    }
}
