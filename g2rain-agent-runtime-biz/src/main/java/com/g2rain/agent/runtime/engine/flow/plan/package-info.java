/**
 * Plan 包：为本轮任务选出技能步骤，并落成可执行任务图。
 *
 * <p>决策要求新建计划时调用；续跑且沿用已有图时跳过。落图后可按技能契约补 provider 前置节点。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.engine.flow.plan;

import org.jspecify.annotations.NullMarked;
