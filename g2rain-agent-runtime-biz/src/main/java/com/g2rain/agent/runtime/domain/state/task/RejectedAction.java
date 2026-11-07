package com.g2rain.agent.runtime.domain.state.task;


import tools.jackson.databind.JsonNode;

/**
 * 用户拒绝的待批动作：写入任务记录，供后续任务还原用户已给出的内容。
 *
 * @param toolName 被拒绝的工具名
 * @param args     参数简报（已脱敏、截断）
 * @author alpha
 * @since 2026/11/7
 */
public record RejectedAction(String toolName, JsonNode args) {
}
