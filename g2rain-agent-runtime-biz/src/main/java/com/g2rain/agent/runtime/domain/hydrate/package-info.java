/**
 * 技能与流程契约：本轮装载后的 Skill、输入输出与完成目标。
 *
 * <p>
 * 领域模型以 {@link com.g2rain.agent.runtime.domain.hydrate.Skill} 为入口，
 * 配套 SkillContract、InputSpec、OutputSpec 等流程类型。
 * 动作语义模型（ToolSpec、ArgSpec 等）在 {@code com.g2rain.agent.runtime.spec}；
 * 装载期把 {@code from} 解析成 {@link com.g2rain.agent.runtime.spec.ValueRef}，执行期只读契约不再重解析原文。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.domain.hydrate;

import org.jspecify.annotations.NullMarked;
