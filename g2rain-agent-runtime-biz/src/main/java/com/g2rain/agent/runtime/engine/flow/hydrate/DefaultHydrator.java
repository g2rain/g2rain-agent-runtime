package com.g2rain.agent.runtime.engine.flow.hydrate;


import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceEvent;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.utils.Collections;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 默认 Hydrator：按已绑定 Agent 装本轮技能列表。
 *
 * <p>只装载技能；Prior 由 {@code turn.start} 生成，本类不写任务记忆。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Component
@RequiredArgsConstructor
public class DefaultHydrator implements Hydrator {

    /**
     * 按 agentId 拉取技能契约与工具名。
     */
    private final BundleLoader bundleLoader;

    /**
     * 有 Agent 主键时写入 {@code ctx.skills} 并输出装载轨迹；没有可用技能时以 WARN 输出。否则跳过。
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

        if (Objects.isNull(agent) || Objects.isNull(agent.getAgentId())) {
            return;
        }

        List<Skill> skills = bundleLoader.load(agent.getAgentId());
        context.setSkills(skills);
        TraceEvent event = Trace.at(TraceStage.HYDRATE, "loaded")
            .kv("agentId", agent.getAgentId())
            .kv("skillCount", Objects.isNull(skills) ? 0 : skills.size())
            .kv("skills", Objects.isNull(skills) ? null : skills.stream().map(Skill::skillKey).toList());
        if (Collections.isEmpty(skills)) {
            event.warn();
        } else {
            event.info();
        }
    }
}
