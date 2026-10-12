package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 本轮交互历史：保留最近若干条消息，并分别记录已刷到 Redis 和已落到库表的位置。
 *
 * <p>超出保留条数时裁掉最早的消息，同步回退两个游标，避免增量刷写跳过仍需写入的消息。</p>
 *
 * @author alpha
 * @since 2026/10/12
 */
final class DialogueLog {

    /**
     * 保留条数。
     */
    private final int max;

    /**
     * 会话对话（含中间文字）。
     */
    private final List<DialogueMessage> messages;

    /**
     * 已 flush 到 Redis 的条数（之后的才 LPUSH）。
     */
    private int flushed;

    /**
     * 已 persist 到 MySQL 的条数（之后的才 insert chat_message）。
     */
    private int persisted;

    /**
     * @param loaded 已加载的交互历史，视为已刷写、已落库
     * @param max    保留条数
     */
    DialogueLog(List<DialogueMessage> loaded, int max) {
        this.max = max;
        this.messages = new ArrayList<>(loaded);
        this.flushed = messages.size();
        this.persisted = messages.size();
    }

    /**
     * 追加一条消息，超出保留条数时裁掉最早的。
     *
     * @param message 待追加消息
     */
    void append(DialogueMessage message) {
        messages.add(message);
        int overflow = messages.size() - max;
        if (overflow > 0) {
            messages.subList(0, overflow).clear();
            flushed = Math.max(0, flushed - overflow);
            persisted = Math.max(0, persisted - overflow);
        }
    }

    /**
     * 路由与提示用的对话窗口：节点内部的 Agent 消息不进入，只保留最后 {@code limit} 条。
     *
     * @param limit 最多返回的消息数量
     * @return 不可变的对话窗口
     */
    List<DialogueMessage> window(int limit) {
        // 路由/投影只要不带 nodeId 的用户消息与最终回复
        List<DialogueMessage> routed = messages.stream()
            .filter(message -> message.getRole() != DialogueRole.AGENT || Objects.isNull(message.getNodeId()))
            .toList();
        int from = Math.max(0, routed.size() - limit);
        return List.copyOf(routed.subList(from, routed.size()));
    }

    /**
     * @return 全部消息快照
     */
    List<DialogueMessage> all() {
        return List.copyOf(messages);
    }

    /**
     * @return 尚未写入 Redis 的消息
     */
    List<DialogueMessage> pendingFlush() {
        return List.copyOf(messages.subList(flushed, messages.size()));
    }

    /**
     * @return 尚未写入 MySQL 的消息
     */
    List<DialogueMessage> pendingPersist() {
        return List.copyOf(messages.subList(persisted, messages.size()));
    }

    /**
     * Redis flush 成功后推进游标。
     */
    void markFlushed() {
        this.flushed = messages.size();
    }

    /**
     * MySQL persist 成功后推进游标。
     */
    void markPersisted() {
        this.persisted = messages.size();
    }
}
