package com.g2rain.agent.runtime.domain.state;


import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * NodeScope（当前节点内存视图）：NodeSpace 与本节点证据摘要。
 * <p>由 {@code RuntimeStateFacade.open} 返回。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public interface NodeScope {

    /**
     * @return 节点 id
     */
    String nodeId();

    /**
     * @return 已绑定的 inputs
     */
    Map<String, Sourced<?>> inputs();

    /**
     * @return 已写出的 outputs
     */
    Map<String, Sourced<?>> outputs();

    /**
     * @return 私有便签（键见 {@link NodeNoteKey}）
     */
    Map<String, Object> state();

    /**
     * @return 本节点证据摘要（不含报文）
     */
    List<EvidenceHead> evidence();

    /**
     * 首次进入时绑定 inputs；委派返回时追加前置节点 outputs。
     *
     * @param inputs 带来源的输入
     */
    void bind(Map<String, Sourced<?>> inputs);

    /**
     * 写一条 output。
     *
     * @param key   输出键
     * @param value 带来源的值
     */
    void output(String key, Sourced<?> value);

    /**
     * 写私有便签。
     *
     * @param key   便签键
     * @param value 便签值；传 {@code null} 表示删除该键
     */
    void note(String key, @Nullable Object value);

    /**
     * 写平台便签。
     *
     * @param key   便签键
     * @param value 便签值；传 {@code null} 表示删除该键
     */
    default void note(NodeNoteKey key, @Nullable Object value) {
        note(key.wire(), value);
    }

    /**
     * 读平台便签。
     *
     * @param key 便签键
     * @return 值；无则 {@code null}
     */
    default @Nullable Object note(NodeNoteKey key) {
        return state().get(key.wire());
    }

    /**
     * 进入本节点下一 ReAct 轮次。
     */
    void round();

    /**
     * 累加本节点消耗的 token。
     *
     * @param tokens 本次模型调用消耗的 token；非负
     */
    void spend(long tokens);

    /**
     * @return 已累计的 ReAct 轮次（与 EvidenceHead.round / DialogueMessage.round 使用同一计数）
     */
    int rounds();

    /**
     * @return 所属本轮视图
     */
    TurnScope turn();
}
