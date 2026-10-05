/**
 * 技能与工具契约：本轮装载后的 Skill、完成条件与参数绑定规格。
 *
 * <p>
 * 领域模型以 {@link com.g2rain.agent.runtime.domain.hydrate.Skill} 为入口，
 * 配套 SkillContract / ToolSpec / ArgSpec / InputSpec / OutputSpec。
 * 装载期把 {@code from}、{@code doneWhen} 解析成 {@link com.g2rain.agent.runtime.domain.hydrate.ValueRef}
 * 与 {@link com.g2rain.agent.runtime.domain.hydrate.DoneWhenExpr}，执行期只读契约不再重解析原文。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.domain.hydrate;

import org.jspecify.annotations.NullMarked;
