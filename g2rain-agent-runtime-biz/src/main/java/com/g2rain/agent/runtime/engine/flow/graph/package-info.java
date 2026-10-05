/**
 * Graph（任务图推进）包：在已开好的执行帧上决定下一步、跑节点并应用副作用。
 *
 * <p>内核产出 {@code GraphStep}，归约器变成副作用（可进入节点执行 / ReAct），
 * 再由副作用处理器发事件、写挂起、停图。节点状态机只负责节点态迁移。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.engine.flow.graph;

import org.jspecify.annotations.NullMarked;
