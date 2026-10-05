package com.g2rain.agent.runtime.engine.flow.hydrate;


import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 默认 Hydrator：按已绑定 Agent 装本轮技能列表。
 *
 * <p>Prior 在 {@code turn.start} 时生成一次，这里不再写入任务记忆种子。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultHydrator implements Hydrator {

    /**
     * 按 agentId 拉取技能契约与工具名。
     */
    private final BundleLoader bundleLoader;

    /**
     * 有 Agent 主键时写入 {@code ctx.skills}；否则跳过。
     *
     * @param context 本轮上下文
     */
    @Override
    public void hydrate(TurnContext context) {
        TurnDecision decision = context.getDecision();
        AgentSelection agent = context.getAgentSelection();
        if (Objects.isNull(agent) && Objects.nonNull(decision)) {
            agent = decision.agent();
        }

        if (Objects.nonNull(agent) && Objects.nonNull(agent.getAgentId())) {
            context.setSkills(bundleLoader.load(agent.getAgentId()));
        }
    }
}
