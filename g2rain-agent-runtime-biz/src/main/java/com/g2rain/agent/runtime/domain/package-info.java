/**
 * <h1>Runtime 领域模型</h1>
 *
 * <p>
 * 承载编排过程中的不可变/可变领域对象：决策与入口结论、执行图与节点、
 * 能力与契约、任务/会话状态切片、工具执行现场等。业务逻辑通过 engine 读写这些模型，
 * 状态持久化经 {@code engine.state.RuntimeStateFacade}，禁止绕过 Facade 直连 Store。
 * </p>
 *
 * <h2>子包</h2>
 * <ul>
 *     <li>{@link com.g2rain.agent.runtime.domain.graph} — 任务图与执行帧</li>
 *     <li>{@link com.g2rain.agent.runtime.domain.state} — 会话/任务/交互历史与证据热冷态</li>
 *     <li>{@link com.g2rain.agent.runtime.domain.hydrate} — 技能与工具契约</li>
 *     <li>{@link com.g2rain.agent.runtime.domain.tool} — 工具调用观测与执行上下文</li>
 *     <li>{@link com.g2rain.agent.runtime.domain.decision} — 选人与意图结果</li>
 *     <li>{@link com.g2rain.agent.runtime.domain.entry} — 入口受理结论</li>
 *     <li>{@link com.g2rain.agent.runtime.domain.execute} — 执行器收口结果</li>
 *     <li>{@link com.g2rain.agent.runtime.domain.finalize} — GoalGate 评估结论</li>
 *     <li>{@link com.g2rain.agent.runtime.domain.plan} — 规划步骤</li>
 *     <li>{@link com.g2rain.agent.runtime.domain.react} — ReAct 循环上下文与结果</li>
 * </ul>
 *
 * @author alpha
 * @since 2026/8/21
 */
package com.g2rain.agent.runtime.domain;
