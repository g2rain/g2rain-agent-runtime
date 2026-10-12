package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.support.RedisKeys;

/**
 * 会话范围的 Redis key 集合。
 *
 * <p>所有 key 使用 {@code organId:conversationId} 作为 Redis hash tag，保证同一会话
 * 的相关数据落在同一个 Redis slot。会话级数据通过无 taskId 的方法访问，任务级数据
 * 通过带 taskId 的方法访问，节点级数据再追加 nodeId。</p>
 *
 * @param organId        机构 id
 * @param conversationId 会话 id
 * @author alpha
 * @since 2026/10/3
 */
public record SessionKeys(String organId, String conversationId) {

    /**
     * 从本轮视图构造会话 key 集合。
     *
     * @param turn 本轮视图
     * @return 与本轮会话绑定的 key 集合
     */
    public static SessionKeys of(TurnScope turn) {
        return new SessionKeys(turn.organId(), turn.conversation().conversationId());
    }

    /**
     * @return 会话栅栏 Hash
     */
    public String state() {
        return RedisKeys.STATE.format(organId, conversationId);
    }

    /**
     * @return 会话级共享 Hash
     */
    public String sessionShared() {
        return RedisKeys.SESSION_SHARED.format(organId, conversationId);
    }

    /**
     * @return 会话对话 List
     */
    public String dialogue() {
        return RedisKeys.DIALOGUE.format(organId, conversationId);
    }

    /**
     * @return 已结束任务记录 List
     */
    public String records() {
        return RedisKeys.RECORDS.format(organId, conversationId);
    }

    /**
     * @return 实体索引 Hash
     */
    public String entities() {
        return RedisKeys.ENTITIES.format(organId, conversationId);
    }

    /**
     * @param taskId 任务 id
     * @return 任务哨兵 Hash
     */
    public String metadata(String taskId) {
        return RedisKeys.METADATA.format(organId, conversationId, taskId);
    }

    /**
     * @param taskId 任务 id
     * @return 任务图 Hash
     */
    public String graph(String taskId) {
        return RedisKeys.GRAPH.format(organId, conversationId, taskId);
    }

    /**
     * @param taskId 任务 id
     * @return 保存任务断点的 key
     */
    public String checkpoint(String taskId) {
        return RedisKeys.CHECKPOINT.format(organId, conversationId, taskId);
    }

    /**
     * @param taskId 任务 id
     * @return 任务级共享 Hash
     */
    public String shared(String taskId) {
        return RedisKeys.SHARED.format(organId, conversationId, taskId);
    }

    /**
     * @param taskId 任务 id
     * @return 任务前情 Redis key
     */
    public String prior(String taskId) {
        return RedisKeys.PRIOR.format(organId, conversationId, taskId);
    }

    /**
     * @param taskId 任务 id
     * @return 写证据索引 Hash
     */
    public String writes(String taskId) {
        return RedisKeys.WRITES.format(organId, conversationId, taskId);
    }

    /**
     * @param taskId 任务 id
     * @param nodeId 节点 id
     * @return 节点空间 Hash
     */
    public String space(String taskId, String nodeId) {
        return RedisKeys.SPACE.format(organId, conversationId, taskId, nodeId);
    }

    /**
     * @param taskId 任务 id
     * @param nodeId 节点 id
     * @return 证据摘要 Hash
     */
    public String evidence(String taskId, String nodeId) {
        return RedisKeys.EVIDENCE.format(organId, conversationId, taskId, nodeId);
    }

    /**
     * @param taskId     任务 id
     * @param evidenceId 证据 id
     * @return 证据报文
     */
    public String payload(String taskId, String evidenceId) {
        return RedisKeys.PAYLOAD.format(organId, conversationId, taskId, evidenceId);
    }
}
