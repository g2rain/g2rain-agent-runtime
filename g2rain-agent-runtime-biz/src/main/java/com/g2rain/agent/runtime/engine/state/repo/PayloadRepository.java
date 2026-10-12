package com.g2rain.agent.runtime.engine.state.repo;

import com.g2rain.agent.runtime.domain.state.task.NodeSpace;
import com.g2rain.agent.runtime.domain.state.task.PayloadType;
import com.g2rain.agent.runtime.dto.TaskNodePayloadDto;
import com.g2rain.agent.runtime.dto.TaskNodePayloadSelectDto;
import com.g2rain.agent.runtime.service.TaskNodePayloadService;
import com.g2rain.agent.runtime.vo.TaskNodePayloadVo;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.web.PrincipalContextHolder;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 报文仓储：{@code task_node_payload} 的读写（证据大报文、节点空间超长字段）。
 *
 * @author alpha
 * @since 2026/10/12
 */
@Component
@RequiredArgsConstructor
public class PayloadRepository {

    /**
     * 超大报文表服务。
     */
    private final TaskNodePayloadService taskNodePayloadService;

    /**
     * 超限证据报文写入 {@code task_node_payload}（ARTIFACT）。
     *
     * @param payloadId   与 evidenceId 相同
     * @param taskId      任务 id
     * @param nodeId      节点 id
     * @param toolName    工具名；可空
     * @param traceId     本轮 trace；可空
     * @param payloadData 完整报文
     */
    public void saveArtifact(String payloadId, String taskId, String nodeId, @Nullable String toolName,
                             @Nullable String traceId, String payloadData) {
        save(payloadId, taskId, nodeId, PayloadType.ARTIFACT, toolName, traceId, payloadData);
    }

    /**
     * 读 ARTIFACT 行的证据报文。
     *
     * @param payloadId 与 evidenceId 相同
     * @return 报文 JSON；没有为 {@code null}
     */
    public @Nullable String loadArtifact(String payloadId) {
        return load(payloadId, PayloadType.ARTIFACT);
    }

    /**
     * 超长 NodeSpace Hash 字段写入 {@code task_node_payload}（OUTPUT）。
     *
     * @param payloadId   新 id
     * @param taskId      任务 id
     * @param nodeId      节点 id
     * @param field       Hash field 名（inputs / outputs / state / actions 等）
     * @param traceId     本轮 trace；可空
     * @param payloadData 字段完整 JSON
     */
    public void saveSpaceField(String payloadId, String taskId, String nodeId, String field,
                               @Nullable String traceId, String payloadData) {
        save(payloadId, taskId, nodeId, PayloadType.OUTPUT, field, traceId, payloadData);
    }

    /**
     * 读 OUTPUT 行的字段 JSON。
     *
     * @param payloadId 占位 id
     * @return 字段 JSON；没有则为 {@code null}
     */
    public @Nullable String loadSpaceField(String payloadId) {
        return load(payloadId, PayloadType.OUTPUT);
    }

    /**
     * 写 {@code task_node_payload} 一行。
     *
     * @param payloadId   报文 id
     * @param taskId      任务 id
     * @param nodeId      节点 id
     * @param type        报文类型
     * @param toolName    工具名；可空
     * @param traceId     追踪 id；可空
     * @param payloadData 报文体
     */
    private void save(String payloadId, String taskId, String nodeId, PayloadType type,
                             @Nullable String toolName, @Nullable String traceId, String payloadData) {
        TaskNodePayloadDto dto = new TaskNodePayloadDto();
        dto.setOrganId(PrincipalContextHolder.getOrganId());
        dto.setPayloadId(payloadId);
        dto.setTaskId(taskId);
        dto.setNodeId(nodeId);
        dto.setPayloadType(type.name());
        dto.setToolName(toolName);
        dto.setTraceId(traceId);
        dto.setPayloadData(payloadData);
        taskNodePayloadService.save(dto);
    }

    /**
     * 按 payloadId + 类型读一行。
     *
     * @param payloadId 报文 id
     * @param type      报文类型
     * @return 报文体；没有为 {@code null}
     */
    private @Nullable String load(String payloadId, PayloadType type) {
        TaskNodePayloadSelectDto query = new TaskNodePayloadSelectDto();
        query.setPayloadId(payloadId);
        query.setPayloadType(type.name());
        List<TaskNodePayloadVo> list = taskNodePayloadService.selectList(query);
        return Collections.isEmpty(list) ? null : list.getFirst().getPayloadData();
    }
}
