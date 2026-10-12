package com.g2rain.agent.runtime.engine.flow.decision;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.g2rain.agent.runtime.domain.decision.AgentSelection;
import com.g2rain.agent.runtime.domain.decision.TurnScene;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.task.TaskRecord;
import com.g2rain.common.utils.Collections;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 意图分类的本轮输入。
 *
 * <p>由 {@link DefaultTurnDispatcher} 在分类前组装，经
 * {@link com.g2rain.agent.runtime.support.prompt.IntentPrompt#USER} 渲染：{@code scene} 与 {@code userInput}
 * 各占独立段落，其余字段序列化为【会话上下文】JSON（空值不输出）。字段只给分类所需摘要，不塞完整证据。</p>
 *
 * <pre>
 * agentLocked — 是否已指定数字员工；为 true 时候选只有该员工，不允许换人。
 * continuityAgentId — 前情所属 Agent 主键；带前情开局时优先沿用。
 * continuation — 上一任务目标摘要、综述与实体键，判断本轮是否依赖上一任务。
 * agentCandidates — 可选 Agent 名单与人设；模型只能从这里选 agentId。
 * dialogue — 交互历史窗口：折叠文本 + 近期原文，看指代与追问。
 * recentEpisodes — 近期回合摘要，只补充语境。
 * skillCatalog — 候选 Agent 的技能短目录，供选人语义匹配。
 * activeTask — 活动任务与断点摘要，供续跑、换题、换人、取消判断。
 * </pre>
 *
 * @param scene             本轮场景；不进入上下文 JSON
 * @param agentLocked       是否已指定数字员工
 * @param userInput         本轮用户原文；不进入上下文 JSON，可空
 * @param continuityAgentId 前情关联 Agent 主键；无可空
 * @param continuation      存活任务前情摘要；无则为 {@code null}
 * @param agentCandidates   可选 Agent 画像列表
 * @param dialogue          交互历史窗口；无可空
 * @param recentEpisodes    近期回合摘要摘抄；可空
 * @param skillCatalog      技能短目录
 * @param activeTask        活动任务与断点摘要；无活动任务为 {@code null}
 * @param model             本轮选用的模型名；不进入上下文 JSON，可空
 * @author alpha
 * @since 2026/8/21
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntentRequest(@JsonIgnore TurnScene scene, boolean agentLocked, @JsonIgnore @Nullable String userInput,
                            @Nullable Long continuityAgentId, @Nullable ContinuitySnippet continuation,
                            List<AgentSelection> agentCandidates, @Nullable DialogueWindow dialogue,
                            @Nullable String recentEpisodes, List<SkillCatalogItem> skillCatalog,
                            @Nullable ActiveTask activeTask, @JsonIgnore @Nullable String model) {

    /**
     * 意图分类用的任务前情摘要：只取目标、综述和实体键。
     *
     * <p>三项足以判断是否接着上一 Task；证据细节留给后续 Hydrate / Plan / ReAct。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContinuitySnippet(
        @Nullable String goalSummary, @Nullable String synthesis, List<String> entities) {

        public ContinuitySnippet {
            entities = List.copyOf(entities);
        }

        /**
         * 从任务记录取出意图分类要用的摘要字段。
         *
         * @param record 任务记录；可空
         * @return 摘要；无记录则为 {@code null}
         */
        public static @Nullable ContinuitySnippet from(@Nullable TaskRecord record) {
            if (Objects.isNull(record)) {
                return null;
            }

            return new ContinuitySnippet(
                record.goal(),
                record.summary(),
                List.copyOf(record.entities().keySet())
            );
        }
    }

    /**
     * 意图分类用的交互历史窗口。
     *
     * @param overflowTranscript 较早消息的折叠文本；当前组装入口恒为 {@code null}
     * @param hotTurns           近期原文发言，看指代与追问
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DialogueWindow(@Nullable String overflowTranscript, List<HotTurn> hotTurns) {

        public DialogueWindow {
            hotTurns = List.copyOf(hotTurns);
        }

        /**
         * 从交互历史取出意图分类用的窗口。
         *
         * @param messages 交互历史；可空或无内容
         * @return 窗口；无可用内容则为 {@code null}
         */
        public static @Nullable DialogueWindow from(@Nullable List<DialogueMessage> messages) {
            if (Collections.isEmpty(messages)) {
                return null;
            }

            List<HotTurn> hot = messages.stream().map(o -> {
                var role = Objects.nonNull(o.getRole()) ? o.getRole().name() : null;
                return new HotTurn(role, o.getText());
            }).toList();
            return new DialogueWindow(null, hot);
        }
    }

    /**
     * 近期交互历史里的一条发言。
     *
     * @param role 角色名，如 USER / AGENT；可空
     * @param text 发言正文；可空
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HotTurn(@Nullable String role, @Nullable String text) {

    }

    /**
     * 技能短目录项：只给 key、名称与简述，供选人语义匹配。
     *
     * @param agentId   所属 Agent 主键；可空
     * @param skillKey  技能键
     * @param skillName 技能显示名；可空
     * @param shortDesc 语义短描述；可空
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SkillCatalogItem(@Nullable Long agentId, String skillKey, @Nullable String skillName,
                                   @Nullable String shortDesc) {
    }

    /**
     * 活动任务与断点摘要，供分类器判断续跑、换题、换人或取消。
     *
     * @param goalSummary    任务目标摘要；可空
     * @param checkpointType 断点类型名；无断点可空
     * @param expectedFields 断点仍缺的字段路径
     * @param writePending   是否存在待审批或待执行写工具
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActiveTask(@Nullable String goalSummary, @Nullable String checkpointType,
                             List<String> expectedFields, boolean writePending) {

        public ActiveTask {
            expectedFields = List.copyOf(expectedFields);
        }
    }
}
