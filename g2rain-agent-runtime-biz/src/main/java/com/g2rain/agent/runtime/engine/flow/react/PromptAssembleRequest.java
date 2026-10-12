package com.g2rain.agent.runtime.engine.flow.react;


import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.engine.tools.guard.EvidenceReader;
import com.g2rain.model.nexus.schema.ChatMessage;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * PromptAssembleRequest：ReAct 一轮模型调用的组装入参。
 *
 * <p>system = {@link com.g2rain.agent.runtime.support.prompt.ReactPrompt}；
 * user 内容由 {@link DefaultPromptAssembler} 根据当前节点现场生成。
 *
 * @param historyMessages 已有对话历史
 * @param toolCallbacks   本节点可用工具
 * @param node            当前节点视图
 * @param nodeId          当前节点 ID
 * @param dependsOn       依赖节点 ID 列表
 * @param skillManual     技能操作手册；可空
 * @param objective       本节点目标；可空
 * @param userGoal        开任务时的用户原文；可空
 * @param delegateCatalog 可委派技能目录文案；可空
 * @param profileText     Agent 人设文案；可空
 * @param ambient         执行环境（如组织/用户）；可空
 * @param contract        当前 Skill 契约；可空
 * @param target          节点完成目标（写工具注册名）；可空
 * @param targetClaimed   完成目标由首次写调用认领
 * @param upstream        上游节点的查询结果原文
 * @author alpha
 * @since 2026/8/21
 */
public record PromptAssembleRequest(List<ChatMessage> historyMessages, List<ToolCallback> toolCallbacks,
                                    NodeScope node, String nodeId, List<String> dependsOn,
                                    @Nullable String skillManual, @Nullable String objective, @Nullable String userGoal,
                                    @Nullable String delegateCatalog,
                                    @Nullable String profileText,
                                    @Nullable JsonNode ambient, @Nullable SkillContract contract,
                                    @Nullable String target, boolean targetClaimed,
                                    List<EvidenceReader.UpstreamExtract> upstream) {
}
