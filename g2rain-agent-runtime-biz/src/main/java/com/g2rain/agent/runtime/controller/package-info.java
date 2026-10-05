/**
 * <h1>会话 HTTP / SSE 入口</h1>
 *
 * <p>
 * 对外暴露智能体对话接口。客户端 POST 本轮请求后，经 SSE 长连接接收进度帧与收口帧。
 * </p>
 *
 * <ul>
 *     <li>{@link com.g2rain.agent.runtime.controller.ConversationController} — {@code /v1/chat/completions} 流式入口</li>
 *     <li>其余 *Controller — 运行态表 CRUD（任务实例、图节点/边、检查点、工具调用记录等），供运维/审计查询</li>
 * </ul>
 *
 * <h2>注意事项</h2>
 * <ul>
 *     <li>业务编排在 {@code ConversationService}；本包只负责协议与流注册。</li>
 * </ul>
 *
 * @author alpha
 * @since 2026/8/21
 */
package com.g2rain.agent.runtime.controller;
