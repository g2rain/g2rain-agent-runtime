/**
 * 任务侧领域模型：元数据、检查点、节点空间、证据摘要、任务前情与回合摘要。
 *
 * <p>
 * 覆盖 {@link com.g2rain.agent.runtime.domain.state.task.TaskMetadata}、
 * {@link com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint}、
 * {@link com.g2rain.agent.runtime.domain.state.task.NodeSpace}、
 * {@link com.g2rain.agent.runtime.domain.state.task.EvidenceHead}、
 * {@link com.g2rain.agent.runtime.domain.state.task.Prior} 与
 * {@link com.g2rain.agent.runtime.domain.state.task.TaskRecord}。
 * 活跃调度读元数据与检查点；收口后任务前情与回合摘要只读供后续 Turn（本轮）衔接。
 * </p>
 *
 * @author alpha
 * @since 2026/9/9
 */
@NullMarked
package com.g2rain.agent.runtime.domain.state.task;

import org.jspecify.annotations.NullMarked;
