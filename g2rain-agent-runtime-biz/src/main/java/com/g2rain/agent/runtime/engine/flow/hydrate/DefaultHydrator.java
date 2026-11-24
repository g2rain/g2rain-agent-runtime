package com.g2rain.agent.runtime.engine.flow.hydrate;


import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.domain.state.TurnStatus;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.TurnDecision;
import com.g2rain.agent.runtime.engine.TurnResult;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceEvent;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 默认 Hydrator：按已绑定 Agent 装本轮技能列表。
 *
 * <p>只装载技能；Prior 由 {@code turn.start} 生成，本类不写任务记忆。
 * 有任务时把已锁定的 {@code specPins} 传入装载，并把新出现的 pin 写回任务。
 * 技能服务不可用或续跑缺技能时只让本轮失败，不改任务。</p>
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
     * <p>装载调用失败（依赖不可用）或续跑图中缺技能时，本轮失败并 return。</p>
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
        List<Skill> skills;
        try {
            skills = bundleLoader.load(agent.getAgentId(), pins);
        } catch (BusinessException ex) {
            // 只吞掉技能服务不可用；其它业务异常照常抛出
            if (!Objects.equals(ex.getErrorCode(), RuntimeErrorCode.DEPENDENCY_UNAVAILABLE.code())) {
                throw ex;
            }
            failTurn(context, RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, RuntimeMessage.SKILLS_UNAVAILABLE.text());
            return;
        }
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

        List<String> missing = missingSkills(context.getTurn(), skills);
        if (!missing.isEmpty()) {
            Trace.at(TraceStage.HYDRATE, "skills_missing").kv("missing", missing).error();
            failTurn(context, RuntimeErrorCode.SKILL_UNAVAILABLE, RuntimeMessage.TASK_SKILLS_UNAVAILABLE.text());
        }
    }

    /**
     * 本轮失败：只写失败结果，不改任务状态或断点。
     *
     * @param ctx       本轮上下文
     * @param errorCode 错误码
     * @param message   用户可见文案
     */
    private static void failTurn(TurnContext ctx, RuntimeErrorCode errorCode, String message) {
        ctx.setFailed(true);
        TurnResult failed = new TurnResult();
        failed.setStatus(TurnStatus.FAILED);
        failed.setErrorCode(errorCode.code());
        failed.setContent(message);
        ctx.setResult(failed);
    }

    /**
     * 续跑图中引用、但不在装载结果里的技能键（按节点顺序去重；空白 skillKey 跳过）。
     *
     * <p>图为空（本轮新建任务）时返回空列表。</p>
     *
     * @param turn   本轮视图
     * @param skills 本轮装载的技能；可空
     * @return 缺失技能键；没有为空列表
     */
    private static List<String> missingSkills(TurnScope turn, @Nullable List<Skill> skills) {
        TaskGraph graph = turn.graph();
        if (Collections.isEmpty(graph.getNodes())) {
            return List.of();
        }

        Set<String> loaded = new HashSet<>();
        if (Objects.nonNull(skills)) {
            for (Skill skill : skills) {
                if (Strings.isNotBlank(skill.skillKey())) {
                    loaded.add(skill.skillKey());
                }
            }
        }

        List<String> missing = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TaskNode node : graph.getNodes()) {
            String key = node.getSkillKey();
            if (Strings.isBlank(key) || seen.contains(key) || loaded.contains(key)) {
                continue;
            }
            seen.add(key);
            missing.add(key);
        }
        return missing;
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

        Map<String, String> pins = task.getSpecPins();
        if (pins.isEmpty()) {
            return Map.of();
        }

        return Map.copyOf(pins);
    }

    /**
     * 把装载得到、任务里尚未有的 pin 写入 {@code specPins}；已有的不覆盖。
     *
     * @param task   当前任务；可空
     * @param skills 本轮技能；可空
     */
    private static void recordPins(@Nullable TaskMetadata task, @Nullable List<Skill> skills) {
        if (Objects.isNull(task) || Collections.isEmpty(skills)) {
            return;
        }

        Map<String, String> pins = task.getSpecPins();
        for (Skill skill : skills) {
            String pin = skill.pin();
            if (Strings.isBlank(pin)) {
                continue;
            }

            // 已锁定的动作语义版本标记不覆盖，保证同任务跨轮一致
            if (pins.containsKey(skill.skillKey())) {
                continue;
            }

            pins.put(skill.skillKey(), pin);
        }
    }
}
