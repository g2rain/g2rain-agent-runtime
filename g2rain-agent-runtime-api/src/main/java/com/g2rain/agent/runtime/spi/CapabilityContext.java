package com.g2rain.agent.runtime.spi;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/**
 * 能力工具的调用现场：身份、节点与本任务已成功的证据。
 *
 * @author alpha
 * @since 2026/11/24
 */
public interface CapabilityContext {

    /**
     * @return 机构 id
     */
    String organId();

    /**
     * @return 用户 id
     */
    String userId();

    /**
     * @return 会话 id
     */
    String conversationId();

    /**
     * @return 任务 id
     */
    String taskId();

    /**
     * @return 节点 id
     */
    String nodeId();

    /**
     * @return 当前技能键；可空
     */
    @Nullable
    String skillKey();

    /**
     * @return 请求语言标签（如 {@code zh-CN}）
     */
    String locale();

    /**
     * 本节点与上游节点已成功的业务调用，按发生顺序。
     *
     * @return 证据摘要；没有时为空列表
     */
    List<EvidenceView> evidence();

    /**
     * 一条证据的完整响应；不属于本任务时为空。
     *
     * @param evidenceId 证据 id
     * @return 完整响应；不可达或缺失时为空
     */
    Optional<JsonNode> payload(String evidenceId);

    /**
     * 为本轮回复添加附件。
     *
     * @param attachment 附件
     */
    void attach(Attachment attachment);
}
