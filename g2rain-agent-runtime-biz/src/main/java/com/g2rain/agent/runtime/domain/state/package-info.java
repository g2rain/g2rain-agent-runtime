/**
 * 运行时状态切片：Turn（本轮）视图、平台快照、会话栅栏、来源证明、节点便签与会话/任务/交互历史模型。
 *
 * <p>
 * 编排通过 {@link com.g2rain.agent.runtime.domain.state.TurnScope} 读写本轮改动，
 * 通过 {@link com.g2rain.agent.runtime.domain.state.NodeScope} 读写当前节点空间；
 * 持久化仍经 {@code RuntimeStateFacade}，本包类型不直连存储。
 * </p>
 *
 * @author alpha
 * @since 2026/9/9
 */
@NullMarked
package com.g2rain.agent.runtime.domain.state;

import org.jspecify.annotations.NullMarked;
