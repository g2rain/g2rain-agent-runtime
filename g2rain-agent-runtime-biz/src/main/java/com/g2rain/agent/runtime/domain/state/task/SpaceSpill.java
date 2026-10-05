package com.g2rain.agent.runtime.domain.state.task;

import com.g2rain.common.utils.Strings;

import java.util.Objects;

/**
 * 节点空间超出热状态字段限制时使用的报文占位。
 *
 * <p>热状态只保存 {@link #KIND} 和 {@code payloadId}，完整 JSON 由对应的冷存储记录保存；
 * 读取方必须先调用 {@link #valid()} 判断占位是否完整，再按 payload id 回源。</p>
 *
 * @param kind      固定 {@link #KIND}，避免与业务 JSON 撞车
 * @param payloadId {@code task_node_payload.payload_id}
 * @author alpha
 * @since 2026/10/3
 */
public record SpaceSpill(String kind, String payloadId) {

    /**
     * 占位类型标记。
     */
    public static final String KIND = "SPACE_SPILL";

    /**
     * @param payloadId 落库 id
     * @return 占位
     */
    public static SpaceSpill of(String payloadId) {
        return new SpaceSpill(KIND, payloadId);
    }

    /**
     * 判断当前记录是否是可回源的节点空间占位。
     *
     * @return 类型标记正确且 payload id 非空时为 {@code true}
     */
    public boolean valid() {
        return Objects.equals(KIND, kind) && Strings.isNotBlank(payloadId);
    }
}
