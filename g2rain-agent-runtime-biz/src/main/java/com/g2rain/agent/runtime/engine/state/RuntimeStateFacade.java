package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.react.ChatTurn;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.tool.ToolCall;
import com.g2rain.agent.runtime.domain.tool.ToolResult;
import com.g2rain.agent.runtime.engine.TurnRequest;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 运行时状态的统一读写入口。
 *
 * <p>编排层通过本接口操作会话、任务、节点、对话和工具证据；本轮改动先保存在
 * {@link TurnScope} 与 {@link NodeScope}，再由 {@link #flush(TurnScope)} 刷新热状态，
 * 最后由 {@link #persist(TurnScope)} 写入持久化存储。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public interface RuntimeStateFacade {

    /**
     * 开始一次请求：回灌状态、登记平台上下文和用户消息，并建立本轮可写视图。
     *
     * @param request 本轮请求
     * @return 本轮内存视图
     */
    TurnScope begin(TurnRequest request);

    /**
     * 打开节点：加载 NodeSpace 与本节点证据摘要；首次进入时绑定 inputs；处理 UNKNOWN 证据。
     *
     * @param turn     本轮视图
     * @param nodeId   节点 id
     * @param contract 当前节点技能契约；可空
     * @return 当前节点视图
     */
    NodeScope open(TurnScope turn, String nodeId, @Nullable SkillContract contract);

    /**
     * 为写工具登记调用意图并执行调用前保护检查。
     *
     * <p>只有返回结果允许继续时，调用方才能真正调用工具；被复用或被阻断的结果都不得
     * 再次发起外部副作用。</p>
     *
     * @param node 当前节点
     * @param call 工具调用
     * @return 预写结果；仅 {@link PrewriteResult.Proceed} 允许调写工具
     */
    PrewriteResult prewrite(NodeScope node, ToolCall call);

    /**
     * 接收写工具返回值并完成对应证据结算。
     *
     * <p>即使结果表示失败或不确定，也必须保留调用结果和状态，供恢复、核查和审计使用。</p>
     *
     * @param node       当前节点
     * @param evidenceId 预写证据 id
     * @param result     工具结果
     */
    void settle(NodeScope node, String evidenceId, ToolResult result);

    /**
     * 记录真实发起的非写工具调用（before 短路不走本方法）。
     *
     * @param node   当前节点
     * @param call   工具调用
     * @param result 调用结果
     * @return 证据 id
     */
    String record(NodeScope node, ToolCall call, ToolResult result);

    /**
     * 按需读取证据报文；Redis 未命中时回源 MySQL。
     *
     * @param turn       本轮视图
     * @param taskId     任务 id
     * @param evidenceId 证据 id
     * @return 报文；没有则为 JSON null 节点
     */
    JsonNode payload(TurnScope turn, String taskId, String evidenceId);

    /**
     * 懒加载其他节点的证据摘要。
     *
     * @param turn   本轮视图
     * @param nodeId 节点 id
     * @return 证据摘要列表
     */
    List<EvidenceHead> evidenceOf(TurnScope turn, String nodeId);

    /**
     * 读取依赖节点已完成的 outputs；只返回 DONE 节点。
     *
     * @param turn    本轮视图
     * @param nodeIds 依赖节点 id
     * @return 键为 {@code nodeId.key} 的 outputs
     */
    Map<String, Sourced<?>> outputsOf(TurnScope turn, List<String> nodeIds);

    /**
     * 重建节点跨轮历史。
     *
     * @param node 当前节点
     * @return 历史轮次
     */
    List<ChatTurn> history(NodeScope node);

    /**
     * 本轮未落库的 assistant/tool 消息，供同轮继续 ReAct。
     *
     * @param node 当前节点
     * @return 本轮消息
     */
    List<ChatTurn> turnMessages(NodeScope node);

    /**
     * 将敏感值写入 secret key，返回短期 handle；不得进入 Scope 投影或 MySQL。
     *
     * @param turn      本轮视图
     * @param value     明文
     * @param expiresAt 过期时间（epoch 毫秒）
     * @return secret handle
     */
    String putSecret(TurnScope turn, Object value, long expiresAt);

    /**
     * 把本轮改动写入 Redis（Lua，校验栅栏）。
     *
     * @param turn 本轮视图
     */
    void flush(TurnScope turn);

    /**
     * 将本轮热状态写入持久化存储并完成任务记录收口。
     *
     * <p>方法只在热状态刷新成功后调用；持久化完成后再按任务状态处理历史记录和生命周期
     * 数据，避免把未成功刷新的内存视图当成权威结果。</p>
     *
     * @param turn 本轮视图
     */
    void persist(TurnScope turn);
}
