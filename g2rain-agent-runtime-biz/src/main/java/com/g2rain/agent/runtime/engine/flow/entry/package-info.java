/**
 * Entry（入口闸门）包：本轮请求进入流水线前的校验与分流。
 *
 * <p>校验显式 Agent，并按活动任务、挂起断点与恢复动作给出受理、拒回、换 Agent 或强制续跑结论。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.engine.flow.entry;

import org.jspecify.annotations.NullMarked;
