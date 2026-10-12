package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.NodeAction;
import com.g2rain.common.utils.Collections;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link NodeScope} 的默认可变实现。
 *
 * <p>对象保存单个节点的输入、输出、私有状态、证据摘要和执行统计。访问器返回
 * 快照，写入方法只修改当前轮内存视图，最终由状态门面统一刷新和持久化。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public final class DefaultNodeScope implements NodeScope {

    /**
     * 所属本轮视图。
     */
    private final DefaultTurnScope turn;

    /**
     * 任务图节点的唯一标识。
     */
    private final String nodeId;

    /**
     * 已绑定 inputs；打开节点后由 InputBinder 补尚未存在的键。
     */
    private final Map<String, Sourced<?>> inputs;

    /**
     * 已写出 outputs；同名允许覆盖。
     */
    private final Map<String, Sourced<?>> outputs;

    /**
     * 节点私有便签（awaiting、委派结果等）。
     */
    private final Map<String, Object> state;

    /**
     * 本节点证据摘要。
     */
    private final List<EvidenceHead> evidence;

    /**
     * 无业务证据的动作，按发生顺序。
     */
    private final List<NodeAction> actions;

    /**
     * ReAct 轮次。
     */
    private int rounds;

    /**
     * 累计 token。
     */
    private long tokens;

    /**
     * @param turn     本轮视图
     * @param nodeId   节点 id
     * @param inputs   已绑定 inputs
     * @param outputs  已有 outputs
     * @param state    私有便签
     * @param evidence 证据摘要
     * @param actions  无业务证据的动作
     * @param rounds   已有轮次
     * @param tokens   已有 token
     */
    public DefaultNodeScope(DefaultTurnScope turn, String nodeId, Map<String, Sourced<?>> inputs,
                            Map<String, Sourced<?>> outputs, Map<String, Object> state,
                            List<EvidenceHead> evidence, List<NodeAction> actions,
                            int rounds, long tokens) {
        this.turn = turn;
        this.nodeId = nodeId;
        this.inputs = new LinkedHashMap<>(inputs);
        this.outputs = new LinkedHashMap<>(outputs);
        this.state = new LinkedHashMap<>(state);
        this.evidence = new ArrayList<>(evidence);
        this.actions = new ArrayList<>(actions);
        this.rounds = rounds;
        this.tokens = tokens;
    }

    /**
     * 返回所属请求视图。
     *
     * @return 当前节点所在的本轮状态
     */
    @Override
    public DefaultTurnScope turn() {
        return turn;
    }

    /**
     * 返回已执行的 ReAct 轮次数。
     *
     * @return 轮次数
     */
    public int rounds() {
        return rounds;
    }

    /**
     * 返回本节点累计消耗的 token 数。
     *
     * @return token 累计
     */
    public long tokens() {
        return tokens;
    }

    /**
     * 返回图中当前节点的唯一标识。
     *
     * @return 节点 id
     */
    @Override
    public String nodeId() {
        return nodeId;
    }

    /**
     * 返回当前输入的不可变快照。
     *
     * @return 参数名到带来源值的映射
     */
    @Override
    public Map<String, Sourced<?>> inputs() {
        return Map.copyOf(inputs);
    }

    /**
     * 返回当前输出的不可变快照。
     *
     * @return 输出名到带来源值的映射
     */
    @Override
    public Map<String, Sourced<?>> outputs() {
        return Map.copyOf(outputs);
    }

    /**
     * 返回节点私有状态的不可变快照。
     *
     * @return 节点便签和运行时辅助状态
     */
    @Override
    public Map<String, Object> state() {
        return Map.copyOf(state);
    }

    /**
     * 返回证据摘要的不可变快照。
     *
     * @return 当前节点的工具证据摘要
     */
    @Override
    public List<EvidenceHead> evidence() {
        return List.copyOf(evidence);
    }

    /**
     * 新增或替换本节点的一条证据摘要。
     *
     * <p>按 evidence id 去重：同一调用的结算结果会替换预写摘要，而不会在列表中产生两条
     * 记录。</p>
     *
     * @param head 证据摘要
     */
    public void addEvidence(EvidenceHead head) {
        evidence.removeIf(existing -> Objects.equals(existing.evidenceId(), head.evidenceId()));
        evidence.add(head);
    }

    /**
     * @return 无业务证据的动作快照，按发生顺序
     */
    public List<NodeAction> actions() {
        return List.copyOf(actions);
    }

    /**
     * 追加一条无业务证据的动作；超过上限时丢弃最早的。
     *
     * @param action 动作
     * @param limit  本节点保留条数上限；不大于 0 时不限
     */
    public void act(NodeAction action, int limit) {
        actions.add(action);
        while (limit > 0 && actions.size() > limit) {
            actions.removeFirst();
        }
    }

    /**
     * 补充尚未绑定的输入。
     *
     * @param inputs 待绑定输入；为空时不产生变化
     */
    @Override
    public void bind(Map<String, Sourced<?>> inputs) {
        // 输入只补充尚未绑定的字段，避免恢复或重复打开节点覆盖既有来源证明。
        if (Collections.isEmpty(inputs)) {
            return;
        }
        inputs.forEach(this.inputs::putIfAbsent);
    }

    /**
     * 写入一个节点输出。
     *
     * @param key   输出名称
     * @param value 带来源证明的输出值
     */
    @Override
    public void output(String key, Sourced<?> value) {
        // 输出允许覆盖同名临时值；最终来源随最后一次受契约允许的写入保存。
        outputs.put(key, value);
    }

    /**
     * 写入或删除节点私有便签。
     *
     * @param key   便签名称
     * @param value 便签值；为 {@code null} 时删除该名称
     */
    @Override
    public void note(String key, @Nullable Object value) {
        // null 表示删除便签，非 null 才写入节点私有状态。
        if (Objects.isNull(value)) {
            state.remove(key);
            return;
        }

        state.put(key, value);
    }

    /**
     * 记录一轮执行及其 token 消耗。
     *
     * @param tokens 本轮消耗的 token 数；由调用方传入非负值
     */
    @Override
    public void round(int tokens) {
        // 每次进入一轮只增加计数，不允许调用方重置历史统计。
        this.rounds++;
        this.tokens += tokens;
    }
}
