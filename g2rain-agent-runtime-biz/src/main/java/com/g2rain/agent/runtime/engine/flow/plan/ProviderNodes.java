package com.g2rain.agent.runtime.engine.flow.plan;

import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.graph.TaskEdge;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.InputSpec;
import com.g2rain.agent.runtime.domain.hydrate.OutputSpec;
import com.g2rain.agent.runtime.domain.hydrate.ShareScope;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.TurnScope;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.enums.RuntimeMessage;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/**
 * 按技能契约的 {@code inputs.provider} 补前置节点。
 *
 * <p>共享值已在 task/session 中存在时执行期可 SKIPPED。
 * 调用示例：{@code ProviderNodes.expand(graph, skillByKey, turn);} 落图后、focus 前调用。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public final class ProviderNodes {

    /**
     * 禁止实例化：只提供静态补节点方法。
     */
    private ProviderNodes() {

    }

    /**
     * 为缺失且声明了 provider 的 input 插入前置节点，最多扫描 8 轮以防环。
     *
     * @param graph  正在物化的任务图，会被就地改节点与边
     * @param skills 本轮技能表，按 skillKey 查找 provider
     * @param turn   当前会话，用于判断 shared/session 是否已绑定
     */
    public static void expand(TaskGraph graph, Map<String, Skill> skills, TurnScope turn) {
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 8) {
            changed = false;
            for (TaskNode node : new ArrayList<>(graph.getNodes())) {
                Skill skill = skills.get(node.getSkillKey());
                if (Objects.isNull(skill) || Objects.isNull(skill.contract())
                    || Collections.isEmpty(skill.contract().getInputs())) {
                    continue;
                }

                for (Map.Entry<String, InputSpec> entry : skill.contract().getInputs().entrySet()) {
                    if (insert(graph, node, entry.getValue(), skills, turn)) {
                        changed = true;
                    }
                }
            }
        }
    }

    /**
     * 本节点所有 share 输出已在 shared/session 中则跳过执行。
     *
     * @param turn  当前会话
     * @param skill 节点技能；无契约或无 share 输出时返回 {@code false}
     * @return 可以 SKIPPED 时为 {@code true}
     */
    public static boolean skipSatisfied(TurnScope turn, @Nullable Skill skill) {
        if (Objects.isNull(skill) || Objects.isNull(skill.contract())
            || Collections.isEmpty(skill.contract().getOutputs())) {
            return false;
        }

        boolean anyShare = false;
        for (Map.Entry<String, OutputSpec> entry : skill.contract().getOutputs().entrySet()) {
            ShareScope share = entry.getValue().getShare();
            if (Objects.isNull(share)) {
                continue;
            }

            anyShare = true;
            Sourced<?> value = share == ShareScope.SESSION
                ? turn.sessionShared().get(entry.getKey())
                : turn.shared().get(entry.getKey());
            // 任一 share 输出缺失：不能 SKIPPED，必须真跑
            if (Objects.isNull(value)) {
                return false;
            }
        }

        return anyShare;
    }

    /**
     * 把已有共享值抄到当前节点 outputs，供 SKIPPED 节点仍能向下游提供值。
     *
     * @param turn  当前会话
     * @param node  当前节点作用域
     * @param skill 含 share 输出声明的技能
     */
    public static void copySharedOutputs(TurnScope turn, NodeScope node, Skill skill) {
        SkillContract contract = skill.contract();
        if (Objects.isNull(contract)) {
            return;
        }

        contract.getOutputs().forEach((key, spec) -> {
            if (Objects.isNull(spec.getShare())) {
                return;
            }

            Sourced<?> value = spec.getShare() == ShareScope.SESSION
                ? turn.sessionShared().get(key) : turn.shared().get(key);
            if (Objects.nonNull(value)) {
                node.output(key, value);
            }
        });
    }

    /**
     * 为消费节点插入或连上 provider 节点。
     *
     * @param graph    正在物化的任务图
     * @param consumer 声明了 provider 的消费节点
     * @param spec     当前 input 声明
     * @param skills   本轮技能表
     * @param turn     当前会话，用于判断共享值是否已绑定
     * @return 图结构发生变化时为 {@code true}
     */
    private static boolean insert(TaskGraph graph, TaskNode consumer, InputSpec spec, Map<String, Skill> skills, TurnScope turn) {
        if (Strings.isBlank(spec.getProvider())) {
            return false;
        }

        if (alreadyBound(turn, spec)) {
            return false;
        }

        String providerKey = spec.getProvider().trim();
        // 技能不能把自己当 provider，否则会自环
        if (providerKey.equals(consumer.getSkillKey())) {
            throw new BusinessException(RuntimeErrorCode.PROVIDER_UNAVAILABLE, providerKey);
        }

        if (!skills.containsKey(providerKey)) {
            throw new BusinessException(RuntimeErrorCode.PROVIDER_UNAVAILABLE, providerKey);
        }

        TaskNode existing = graph.getNodes().stream()
            .filter(node -> providerKey.equals(node.getSkillKey()))
            .findFirst()
            .orElse(null);
        if (Objects.nonNull(existing)) {
            return link(graph, consumer, existing.getNodeId());
        }

        TaskNode provider = new TaskNode();
        provider.setNodeId(RuntimeUtils.uuid());
        provider.setSkillKey(providerKey);
        Skill providerSkill = skills.get(providerKey);
        String display = Objects.nonNull(providerSkill) ? Strings.blankToNull(providerSkill.semanticDesc()) : null;
        provider.setTitle(Objects.nonNull(display)
            ? RuntimeMessage.STEP_TITLE_PREPARE.text("name", display)
            : RuntimeMessage.STEP_TITLE_PREPARE.text("name", "所需信息"));
        provider.setObjective(provider.getTitle());
        provider.setStatus(NodeStatus.READY);
        graph.getNodes().add(provider);
        link(graph, consumer, provider.getNodeId());
        return true;
    }

    /**
     * {@code from} 指向的 shared/session 值已存在则不必再插 provider。
     *
     * @param turn 当前会话
     * @param spec 当前 input 声明
     * @return 共享值已绑定时为 {@code true}
     */
    private static boolean alreadyBound(TurnScope turn, InputSpec spec) {
        String from = Strings.trim(spec.getFrom());
        if (Strings.isBlank(from)) {
            return false;
        }

        if (from.startsWith("session.")) {
            return Objects.nonNull(turn.sessionShared().get(from.substring("session.".length())));
        }

        if (from.startsWith("shared.")) {
            return Objects.nonNull(turn.shared().get(from.substring("shared.".length())));
        }

        return false;
    }

    /**
     * 连依赖边；消费节点若已 READY 则退回 PENDING，等 provider 完成。
     *
     * @param graph    正在物化的任务图
     * @param consumer 消费节点
     * @param fromId   provider 节点 id
     * @return 新增边时为 {@code true}
     */
    private static boolean link(TaskGraph graph, TaskNode consumer, String fromId) {
        if (consumer.getDependsOn().contains(fromId) || consumer.getNodeId().equals(fromId)) {
            return false;
        }

        consumer.getDependsOn().add(fromId);
        graph.getEdges().add(new TaskEdge(fromId, consumer.getNodeId()));
        if (consumer.getStatus() == NodeStatus.READY) {
            // 新依赖尚未完成，不能继续当本波可跑节点
            consumer.setStatus(NodeStatus.PENDING);
        }

        return true;
    }
}
