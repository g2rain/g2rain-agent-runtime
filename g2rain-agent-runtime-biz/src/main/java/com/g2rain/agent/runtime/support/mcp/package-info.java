/**
 * 外部 MCP 工具接入：同步客户端包装与按名暴露的 ToolCallback。
 *
 * <p>本轮能力集声明可用外部工具后，由本包拉清单、建回调，供节点内循环调用。
 * 本包不决定工具是否对本轮可见，也不重试 {@code callTool}。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.support.mcp;

import org.jspecify.annotations.NullMarked;
