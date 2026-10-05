package com.g2rain.agent.runtime.engine.flow.decision;


import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * AgentCatalog（Agent 目录）：查询启用中的 Agent 画像，供路由绑定使用。
 * <p>
 * 只暴露「按 id 取启用画像」与「列出全部启用项」；不负责优先级裁决。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface AgentCatalog {

    /**
     * 按主键查找启用中的 Agent 选人画像。
     *
     * @param agentId Agent 主键
     * @return 启用中的选人结果；不存在或未启用则为 {@code null}
     */
    @Nullable AgentSelection findActiveById(Long agentId);

    /**
     * 列出当前启用中的全部 Agent 选人画像。
     *
     * @return 启用列表；无启用项时为空列表
     */
    List<AgentSelection> listActive();
}
