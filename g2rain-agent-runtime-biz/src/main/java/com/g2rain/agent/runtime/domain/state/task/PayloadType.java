package com.g2rain.agent.runtime.domain.state.task;

/**
 * 节点冷存储报文类型，对应 {@code task_node_payload.payload_type}。
 *
 * <p>热状态放不下的大 JSON 落到冷表后，用本枚举区分证据原文与节点空间字段，回源时按类型读行。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public enum PayloadType {

    /**
     * 证据报文。
     * <p>超过内联上限的工具原始返回，由 {@link EvidenceHead#payloadId()} 回源。</p>
     */
    ARTIFACT,

    /**
     * 节点空间字段。
     * <p>超过内联上限的 inputs/outputs/便签 JSON，由 {@link SpaceSpill} 占位回源。</p>
     */
    OUTPUT
}
