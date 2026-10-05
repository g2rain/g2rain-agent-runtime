package com.g2rain.agent.runtime.domain.state;


/**
 * 值的可追溯来源。
 *
 * <p>{@code type} 说明值来自哪一类事实，{@code ref} 定位具体对象，{@code path} 定位对象内的
 * 字段。三者共同构成参数绑定和审计所需的最小引用；缺少任一定位信息时，调用方应拒绝把值
 * 当作已证明的业务参数。</p>
 *
 * @param type 来源类型
 * @param ref  对象 id（证据 id、节点 id、共享键等）
 * @param path 取值路径
 * @author alpha
 * @since 2026/10/3
 */
public record Source(SourceType type, String ref, String path) {

}
