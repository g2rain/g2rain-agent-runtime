package com.g2rain.agent.runtime.domain.state.dialogue;


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * 交互历史中的一条发言。
 *
 * <p>
 * 保存角色、正文、消息类型与归属（任务/节点/轮次）。USER 消息与最终 AGENT 回复通常无 nodeId；
 * 节点中间文字带 nodeId 与 ReAct round。近期列表保持原文结构，供本轮拼模型上下文。
 * </p>
 *
 * @author alpha
 * @since 2026/9/9
 */
@Getter
@Setter
@NoArgsConstructor
public class DialogueMessage {

    /**
     * 发言角色；USER 为用户原文，AGENT 为回复或确认卡片。
     */
    private @Nullable DialogueRole role;

    /**
     * 发言正文。
     */
    private @Nullable String text;

    /**
     * 消息的唯一标识。
     */
    private @Nullable String messageId;

    /**
     * 当前请求的链路追踪标识，用于关联消息、事件和状态变更。
     */
    private @Nullable String traceId;

    /**
     * 消息类型。
     */
    private @Nullable MessageType type;

    /**
     * 所属任务；无任务轮次为空。
     */
    private @Nullable String taskId;

    /**
     * 节点 id；最终回复与用户消息为空，中间文字非空。
     */
    private @Nullable String nodeId;

    /**
     * ReAct 轮次；非节点中间文字为空。
     */
    private @Nullable Integer round;

    /**
     * 消息创建时间。
     * <p>Unix Epoch 毫秒时间戳，用于保留消息时间顺序。</p>
     */
    private long createdAt;

    /**
     * 本轮耗时毫秒；仅最终回复有值，由 TurnScope 追加时写入。
     */
    private @Nullable Integer elapsedMs;

    /**
     * 本轮选用的模型名；仅用户消息记录，供观测与排查。
     */
    private @Nullable String model;

    /**
     * 用角色和正文创建一条交互历史消息，创建时间为当前毫秒。
     *
     * @param role 发言角色
     * @param text 发言正文
     */
    public DialogueMessage(DialogueRole role, String text) {
        this.role = role;
        this.text = text;
        this.createdAt = System.currentTimeMillis();
    }
}
