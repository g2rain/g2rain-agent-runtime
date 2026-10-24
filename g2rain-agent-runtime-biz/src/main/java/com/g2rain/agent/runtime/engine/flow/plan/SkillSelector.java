package com.g2rain.agent.runtime.engine.flow.plan;


import lombok.extern.slf4j.Slf4j;
import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.engine.TurnContext;
import com.g2rain.agent.runtime.engine.model.ModelGateway;
import com.g2rain.agent.runtime.engine.model.ModelUse;
import com.g2rain.agent.runtime.support.FailureCategory;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.prompt.SkillCandidatePrompt;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.support.ChatCompletionRequests;
import com.g2rain.model.nexus.support.OpenAiChatClientConverter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 技能候选筛选：从白名单中让模型挑出本轮所需技能（含前置技能）。
 *
 * @author alpha
 * @since 2026/10/12
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillSelector {

    /**
     * JSON 编解码器：拼 prompt、解析模型应答。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();


    /**
     * 模型网关。
     */
    private final ModelGateway modelGateway;


    /**
     * 运行时配置：候选技能数量上限。
     */
    private final RuntimeProperties runtimeProperties;


    /**
     * 从白名单筛本轮候选技能。
     *
     * <p>白名单只有 1 个则全用；多个才调模型缩名单，失败退回全量。
     * 输入数据字段用途见 {@link SkillCandidateView}；提示词见 {@link SkillCandidatePrompt}。
     *
     * @param ctx       本轮上下文
     * @param whitelist 技能白名单
     * @param goal      规划目标
     * @return 筛后的候选；模型给出空名单时为空列表，调用异常时退回全量白名单
     */
    List<SkillItem> select(TurnContext ctx, List<SkillItem> whitelist, String goal) {
        if (whitelist.size() == 1) {
            return whitelist;
        }

        try {
            RuntimeProperties.Plan cfg = runtimeProperties.getPlan();
            String userPrompt = JSON.obj2str(new SkillCandidateView(
                ctx.getRequest().getUserInput(), goal, whitelist
            ));

            ChatMessage assistant = OpenAiChatClientConverter.firstMessage(modelGateway.chat(ModelUse.SKILL_CANDIDATE, ctx.getRequest().getModel(),
                ChatCompletionRequests.of().system(SkillCandidatePrompt.SYSTEM).user(SkillCandidatePrompt.USER.formatted(userPrompt)).build()
            ));
            if (Objects.isNull(assistant) || Strings.isBlank(assistant.getContent())) {
                return whitelist;
            }

            // 从模型正文抠出候选 skillKey 列表
            List<String> keys;
            try {
                String raw = RuntimeUtils.extractObject(assistant.getContent());
                CandidateSkillsAnswer answer = JSON.str2obj(raw, CandidateSkillsAnswer.class);
                if (Objects.isNull(answer) || Collections.isEmpty(answer.candidateSkillKeys())) {
                    // 模型给出空名单：视为筛不出候选，交给上层失败，而不是退回全量
                    return List.of();
                }

                keys = answer.candidateSkillKeys().stream()
                    .filter(Strings::isNotBlank)
                    .map(String::trim)
                    .toList();
            } catch (RuntimeException ex) {
                log.error("failed to select", ex);
                Trace.at(TraceStage.PLAN, "candidates_unparsed")
                    .kv("error", ex.getClass().getSimpleName() + ": " + ex.getMessage())
                    .warn();
                keys = List.of();
            }

            Set<String> white = whitelist.stream().map(SkillItem::skillKey).collect(Collectors.toSet());
            Map<String, SkillItem> byKey = whitelist.stream().collect(Collectors.toMap(
                SkillItem::skillKey, s -> s, (a, _) -> a
            ));

            int max = Math.max(1, cfg.getMaxCandidates());
            List<SkillItem> ordered = keys.stream()
                .filter(white::contains)
                .distinct()
                .map(byKey::get)
                .filter(Objects::nonNull)
                .limit(max)
                .toList();

            return ordered.isEmpty() ? whitelist : ordered;
        } catch (RuntimeException ex) {
            // 模型额度、密钥等致命失败不降级，交给收口按分类回复
            FailureCategory.throwIfFatal(ex);
            log.error("failed to select", ex);
            // 调用或解析异常：不阻断规划，退回全量候选
            Trace.at(TraceStage.PLAN, "candidates_failed")
                .kv("error", ex.getClass().getSimpleName() + ": " + ex.getMessage())
                .warn();
            return whitelist;
        }
    }

    /**
     * 技能候选模型的 JSON 形状。
     *
     * @param candidateSkillKeys 模型挑出的 skillKey；可空
     */
    private record CandidateSkillsAnswer(@Nullable List<String> candidateSkillKeys) {

    }

    /**
     * 技能候选筛选的 user JSON。
     *
     * <pre>
     * input  — 用户原话，用来匹配相关技能
     * goal   — 目标摘要，与 input 互补（要对准办成什么）
     * skills — skillKey + semanticDesc：只能从这里挑候选；短描述做相关判断
     * </pre>
     *
     * @param input  用户原话；可空时按空串入模型
     * @param goal   目标摘要；可空时按空串入模型
     * @param skills 候选短目录
     */
    private record SkillCandidateView(@Nullable String input, @Nullable String goal, List<SkillItem> skills) {
        SkillCandidateView {
            input = Objects.toString(input, "");
            goal = Objects.toString(goal, "");
            skills = List.copyOf(skills);
        }
    }
}
