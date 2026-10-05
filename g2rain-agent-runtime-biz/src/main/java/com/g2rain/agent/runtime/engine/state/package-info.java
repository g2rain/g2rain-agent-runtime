/**
 * 运行时状态门面：{@link com.g2rain.agent.runtime.engine.state.RuntimeStateFacade} 为业务唯一写/编排入口。
 * <p>
 * 业务禁止直连 Store / Redis / codegen Service；开局写入记忆、开 Task/Node、工具回写、
 * 任务记忆与溯源、委派帧、检查点、交互历史、收口与薄视图组装均经本包。
 * 只读视图见子包 {@code view}。
 * </p>
 *
 * @author alpha
 * @since 2026/9/9
 */
@NullMarked
package com.g2rain.agent.runtime.engine.state;

import org.jspecify.annotations.NullMarked;
