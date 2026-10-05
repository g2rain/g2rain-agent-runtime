package com.g2rain.agent.runtime.domain.state.task;


/**
 * 工具对外部系统产生副作用的等级。
 *
 * <p>{@link #NONE} 表示只读或无外部改变；{@link #WRITE} 表示可能改变外部系统，并要求
 * 调用前登记、调用后结算以及相应的幂等与确认保护。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public enum SideEffect {

    /**
     * 只读或无外部副作用。
     * <p>查询类工具使用；超时一般可当未完成，不必按写工具做未知核查。</p>
     */
    NONE,

    /**
     * 会改外部系统，调用前须完成预登记，返回后须完成结果结算。
     */
    WRITE
}
