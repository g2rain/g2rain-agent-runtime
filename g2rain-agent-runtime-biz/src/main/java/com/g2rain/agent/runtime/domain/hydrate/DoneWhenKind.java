package com.g2rain.agent.runtime.domain.hydrate;


/**
 * 完成条件原子条款种类。
 *
 * <p>由 {@link DoneWhenParser} 从 {@code doneWhen} 原文识别；{@link #UNKNOWN} 表示装载期应拒收该契约。</p>
 *
 * @author alpha
 * @since 2026/10/4
 */
public enum DoneWhenKind {

    /**
     * 输出字段存在。
     * <p>本节点某 output 存在且非空，例如 {@code outputs.orderId exists}。</p>
     */
    OUTPUT_FIELD_EXISTS,

    /**
     * 输出长度达标。
     * <p>本节点集合 output 长度大于等于 N，例如 {@code outputs.items length >= 1}。</p>
     */
    OUTPUT_LENGTH_GTE,

    /**
     * 集合项字段存在。
     * <p>本节点集合 output 每一项都具备某字段，例如 {@code outputs.items[*].id exists}。</p>
     */
    OUTPUT_ITEM_FIELD_EXISTS,

    /**
     * 目标工具成功。
     * <p>契约 {@code target} 对应的主业务工具已成功。</p>
     */
    TARGET_TOOL_SUCCEEDED,

    /**
     * 指定工具成功。
     * <p>具名工具调用已成功，例如 {@code query_order succeeded}。</p>
     */
    TOOL_SUCCEEDED,

    /**
     * 指定工具结果为空。
     * <p>具名工具已返回且判定为空结果，例如查无记录。</p>
     */
    TOOL_EMPTY,

    /**
     * 证据工具成功。
     * <p>{@code evidence.<tool> succeeded}：该工具在本节点证据中已成功。</p>
     */
    EVIDENCE_TOOL_SUCCEEDED,

    /**
     * 证据键存在。
     * <p>{@code evidence.<key> exists}：抽取语义键存在且对应调用算成功。</p>
     */
    EVIDENCE_KEY_EXISTS,

    /**
     * 无法识别。
     * <p>条款不符合任一形态；语法树含此值时契约不可用。</p>
     */
    UNKNOWN
}
