package com.g2rain.agent.runtime.domain.state.task;


import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import org.jspecify.annotations.Nullable;

/**
 * 节点内一次没有业务证据的动作，供跨轮重建节点历史。
 *
 * <p>业务工具实际执行后由证据记录；以下动作没有证据，由本记录补齐：模型调用内置工具（申请确认、追问、写产出等）、
 * 模型的调用在执行前被拒绝或推迟、用户确认或拒绝挂起。重建时与对话消息、证据按轮次合并。例：</p>
 * <pre>{@code
 * NodeAction.model(3, "call_1", "builtin_node_approval", "{\"toolName\":\"post_article_save\"}", "{\"status\":\"SUSPENDED\"}");
 * NodeAction.user(4, "用户已确认执行 [post_article_save]");
 * }</pre>
 *
 * @param actor     发起方：模型为 {@link DialogueRole#AGENT}，用户为 {@link DialogueRole#USER}
 * @param round     所属 ReAct 轮次
 * @param callId    模型工具调用 id；用户动作为 {@code null}
 * @param toolName  工具名；用户动作为 {@code null}
 * @param arguments 入参；用户动作为 {@code null}
 * @param message   模型动作为返回给模型的说明，用户动作为动作描述
 * @author alpha
 * @since 2026/10/11
 */
public record NodeAction(DialogueRole actor, int round, @Nullable String callId, @Nullable String toolName,
                         @Nullable String arguments, String message) {

    /**
     * 模型的一次工具调用及其返回。
     *
     * @param round     所属轮次
     * @param callId    工具调用 id
     * @param toolName  工具名
     * @param arguments 入参
     * @param message   返回给模型的说明
     * @return 模型动作
     */
    public static NodeAction model(int round, String callId, String toolName, @Nullable String arguments, String message) {
        return new NodeAction(DialogueRole.AGENT, round, callId, toolName, arguments, message);
    }

    /**
     * 用户对挂起的一次回应（确认、拒绝等）。
     *
     * @param round   所属轮次
     * @param message 动作描述
     * @return 用户动作
     */
    public static NodeAction user(int round, String message) {
        return new NodeAction(DialogueRole.USER, round, null, null, null, message);
    }

    /**
     * 复制本动作并替换说明，其余不变；用于按上限裁剪说明。
     *
     * @param message 新说明
     * @return 新动作
     */
    public NodeAction withMessage(String message) {
        return new NodeAction(actor, round, callId, toolName, arguments, message);
    }
}
