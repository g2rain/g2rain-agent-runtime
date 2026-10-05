package com.g2rain.agent.runtime.domain.state;


/**
 * 带来源证明的业务值。
 *
 * <p>值与 {@link Source} 必须一起流转，避免在节点交接或工具调用时只传递裸值而丢失来源。
 * 该 record 不验证来源是否真实存在；验证由读取或写入 State 的边界完成。</p>
 *
 * @param value  业务值
 * @param source 来源
 * @author alpha
 * @since 2026/10/3
 */
public record Sourced<T>(T value, Source source) {

}
