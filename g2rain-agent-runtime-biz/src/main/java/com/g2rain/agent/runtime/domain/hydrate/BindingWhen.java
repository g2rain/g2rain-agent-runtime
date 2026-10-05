package com.g2rain.agent.runtime.domain.hydrate;

/**
 * 参数从数据源绑定到节点输入的时机。
 *
 * <p>装载节点时按该时机决定是否把 {@code from} 解析出的值写入 inputs。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public enum BindingWhen {

    /**
     * 始终绑定。
     * <p>每次装载节点都尝试取值，缺值再走缺参动作。</p>
     */
    ALWAYS,

    /**
     * 唯一时绑定。
     * <p>只有目标集合恰好一项时才写入，避免多项结果绑错行。</p>
     */
    UNIQUE
}
