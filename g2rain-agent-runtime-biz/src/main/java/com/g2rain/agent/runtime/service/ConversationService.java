package com.g2rain.agent.runtime.service;


import com.g2rain.agent.runtime.engine.TurnRequest;
import com.g2rain.agent.runtime.engine.TurnResult;

/**
 * ConversationService（会话服务）：对外提供「发一轮用户输入」的业务入口。
 * <p>
 * Turn（本轮）= 一次用户请求从进入到产出结果的完整处理。
 * 调用方组装 {@link TurnRequest}，经 {@link #send} 跑完本轮后拿回 {@link TurnResult}。
 * 例：用户说「帮我订明早的会议室」→ 填好渠道与用户输入调用 send → 得到含会话 id、状态、回复的结果。
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface ConversationService {

    /**
     * 发送一轮用户请求，驱动本轮流水线并返回结果。
     * <p>
     * 新建会话时可由实现侧补 conversationId；已有会话则在同一会话上串行续聊。
     *
     * @param request 本轮请求（渠道、会话 id、用户输入、可选续跑动作等）
     * @return 本轮执行结果（会话 id、状态、回复文案等）
     */
    TurnResult send(TurnRequest request);
}
