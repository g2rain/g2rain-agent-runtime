package com.g2rain.agent.runtime.engine.flow.hydrate;


import com.g2rain.agent.runtime.domain.hydrate.Skill;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * BundleLoader（能力包加载器）：按 Agent 拉取本轮可用技能列表。
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface BundleLoader {

    /**
     * 加载指定 Agent 的技能列表。
     *
     * @param agentId Agent 主键
     * @return 技能列表；无 Bundle 或无可用技能时为 {@code null}
     */
    @Nullable
    List<Skill> load(long agentId);
}
