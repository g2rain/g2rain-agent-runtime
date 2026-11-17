package com.g2rain.agent.runtime.engine.flow.hydrate;


import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceEvent;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 默认 Hydrator：按已绑定 Agent 装本轮技能列表。
 *
 * <p>只装载技能；Prior 由 {@code turn.start} 生成，本类不写任务记忆。
 * 有任务时把已锁定的 {@code skillVersions} 传入装载，并把新出现的 pin 写回任务。</p>
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

        TaskMetadata task = context.getTurn().task().orElse(null);
        Map<String, String> pins = pinsOf(task);
        List<Skill> skills = bundleLoader.load(agent.getAgentId(), pins);
        context.setSkills(skills);
        recordPins(task, skills);
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

    /**
     * 当前任务已锁定的版本表；无任务时为空 map。
     *
     * @param task 当前任务；可空
     * @return 技能键 → pin
     */
    private static Map<String, String> pinsOf(@Nullable TaskMetadata task) {
        if (Objects.isNull(task)) {
            return Map.of();
        }

        Map<String, String> versions = task.getSkillVersions();
        if (versions.isEmpty()) {
            return Map.of();
        }

        return Map.copyOf(versions);
    }

    /**
     * 把装载得到、任务里尚未有的 pin 写入 {@code skillVersions}；已有的不覆盖。
     *
     * @param task   当前任务；可空
     * @param skills 本轮技能；可空
     */
    private static void recordPins(@Nullable TaskMetadata task, @Nullable List<Skill> skills) {
        if (Objects.isNull(task) || Collections.isEmpty(skills)) {
            return;
        }

        Map<String, String> versions = task.getSkillVersions();
        for (Skill skill : skills) {
            String pin = skill.pin();
            if (Strings.isBlank(pin)) {
                continue;
            }

            // 已锁定的版本不覆盖，保证同任务跨轮一致
            if (versions.containsKey(skill.skillKey())) {
                continue;
            }

            versions.put(skill.skillKey(), pin);
        }
    }
}
