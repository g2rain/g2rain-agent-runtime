package com.g2rain.agent.runtime.engine;


/**
 * 本轮编排执行契约。
 *
 * <p>实现类在已 begin 的 {@link TurnContext} 上跑入口闸门、决策、装载、规划、图执行与收口，
 * 返回 {@link TurnResult}。热态 begin / flush / persist 由会话服务在会话锁内调用，不在本接口内。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface TurnPipeline {

    /**
     * 执行一整轮编排，从入口闸门到收口。
     *
     * @param ctx 已 begin 的本轮上下文；方法会写入决策、技能、结果等字段
     * @return 本轮结论；入口短路时可能不经过完整执行链
     */
    TurnResult run(TurnContext ctx);
}
