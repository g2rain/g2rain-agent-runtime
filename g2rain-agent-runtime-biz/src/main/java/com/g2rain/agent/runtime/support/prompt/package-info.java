/**
 * 各处理步骤使用的 system 纪律文本。
 *
 * <p>本包只保存稳定规则常量；user 现场由组装器按结构化投影生成，不走这些模板。
 * ReAct 的 user 另附 history 与 tools[]。</p>
 *
 * <pre>
 * 调模型入口（system → user 组装类型）：
 *
 * 1. 意图分类
 *    system: IntentPrompt
 *    user:   IntentRequest
 *
 * 2. 技能候选筛选
 *    system: SkillCandidatePrompt
 *    user:   DefaultPlanner.SkillCandidateView
 *
 * 3. 任务规划
 *    system: PlanPrompt
 *    user:   DefaultPlanner.PlanView
 *
 * 4. ReAct 节点执行
 *    system: ReactPrompt
 *    user:   投影 record 序列化后的带标签 JSON（+ history + tools[]）
 *
 * 5. GoalGate 润色
 *    system: GoalGatePrompt
 *    user:   DefaultGoalGate.GoalGateUserView
 * </pre>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.support.prompt;

import org.jspecify.annotations.NullMarked;
