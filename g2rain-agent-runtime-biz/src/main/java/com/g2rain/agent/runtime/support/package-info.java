/**
 * Runtime 横切辅助：键规则、JSON 收窄、平台字段目录、提示词、内置/MCP 工具适配、链路轨迹。
 *
 * <p>本包不保存会话主数据，也不做本轮决策。状态读写走 State 门面，编排走 engine。
 * 子包分工：{@link com.g2rain.agent.runtime.support.prompt} 提示词，
 * {@link com.g2rain.agent.runtime.support.tool} 内置工具元数据，
 * {@link com.g2rain.agent.runtime.support.mcp} 外部工具回调，
 * {@link com.g2rain.agent.runtime.support.trace} 链路轨迹日志。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
package com.g2rain.agent.runtime.support;
