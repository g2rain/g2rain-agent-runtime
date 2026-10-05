/**
 * 交互历史领域模型：发言角色、消息类型、单条发言与审批确认卡片。
 *
 * <p>
 * 只记录 USER/AGENT 实际发生过的发言，不保存工具报文或任务记忆。
 * 近期原文由 {@link com.g2rain.agent.runtime.domain.state.TurnScope} 按条数截取。
 * </p>
 *
 * @author alpha
 * @since 2026/9/9
 */
@NullMarked
package com.g2rain.agent.runtime.domain.state.dialogue;

import org.jspecify.annotations.NullMarked;
