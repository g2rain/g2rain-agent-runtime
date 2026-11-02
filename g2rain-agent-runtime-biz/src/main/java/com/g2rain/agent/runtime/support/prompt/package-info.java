/**
 * 各处理步骤使用的提示词文本。
 *
 * <p>本包只保存稳定的文本常量。单次调用的步骤统一为 SYSTEM（身份与职责）+ USER（完整指令模板），
 * USER 结构为：任务 → 规则 → 输入说明 → 输入数据 → 输出指令，输出指令放在最后、紧挨数据。
 * ReAct 是多轮调用：规则全部在 system，user 为带标签的 JSON 块，另附 history 与 tools[]。</p>
 *
 * <pre>
 * 调模型入口（system → user）：
 *
 * 1. 意图分类
 *    system: IntentPrompt.SYSTEM（填入 RequestLocale.tag()）
 *    user:   IntentPrompt.USER（填入场景、IntentRequest JSON、用户原文）
 *
 * 2. 技能候选筛选
 *    system: SkillCandidatePrompt.SYSTEM
 *    user:   SkillCandidatePrompt.USER（填入 DefaultPlanner.SkillCandidateView JSON）
 *
 * 3. 任务规划
 *    system: PlanPrompt.SYSTEM
 *    user:   PlanPrompt.USER（填入 DefaultPlanner.PlanView JSON）
 *
 * 4. ReAct 节点执行
 *    system: ReactPrompt.TEXT（填入 RequestLocale.tag()）
 *    user:   带标签的 JSON 块（+ history + tools[]）
 *
 * 5. 最终答复整理
 *    system: GoalGatePrompt.SYSTEM（填入 RequestLocale.tag()）
 *    user:   GoalGatePrompt.USER（填入 DefaultGoalGate.GoalGateView JSON）
 * </pre>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.support.prompt;

import org.jspecify.annotations.NullMarked;
