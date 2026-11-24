package com.g2rain.agent.runtime.spi;

/**
 * 一条已成功业务调用的证据摘要，供能力工具只读查看。
 *
 * @param evidenceId 证据 id
 * @param nodeId     所属节点
 * @param toolName   工具名
 * @param round      所在轮次
 * @param empty      结果是否为空
 * @author alpha
 * @since 2026/11/24
 */
public record EvidenceView(String evidenceId, String nodeId, String toolName, int round, boolean empty) {

}
