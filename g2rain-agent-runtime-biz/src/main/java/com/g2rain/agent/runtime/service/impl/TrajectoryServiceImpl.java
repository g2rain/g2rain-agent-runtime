package com.g2rain.agent.runtime.service.impl;

import com.g2rain.agent.runtime.dao.TaskGraphNodeDao;
import com.g2rain.agent.runtime.dao.TaskInstanceDao;
import com.g2rain.agent.runtime.dao.ToolBlockRecordDao;
import com.g2rain.agent.runtime.dao.ToolCallRecordDao;
import com.g2rain.agent.runtime.dao.po.TaskGraphNodePo;
import com.g2rain.agent.runtime.dao.po.TaskInstancePo;
import com.g2rain.agent.runtime.dao.po.ToolBlockRecordPo;
import com.g2rain.agent.runtime.dao.po.ToolCallRecordPo;
import com.g2rain.agent.runtime.dto.TaskInstanceSelectDto;
import com.g2rain.agent.runtime.service.TrajectoryService;
import com.g2rain.agent.runtime.vo.TrajectoryVo;
import com.g2rain.common.utils.Strings;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按会话一次查出任务，再按任务主键集合分别查节点、调用、拦截。
 *
 * @author alpha
 * @since 2026/11/23
 */
@Service("trajectoryServiceImpl")
public class TrajectoryServiceImpl implements TrajectoryService {

    /**
     * 任务按创建时间升序。
     */
    private static final String SORT_CREATE_TIME_ASC = "createTime,asc";

    @Resource
    private TaskInstanceDao taskInstanceDao;

    @Resource
    private TaskGraphNodeDao taskGraphNodeDao;

    @Resource
    private ToolCallRecordDao toolCallRecordDao;

    @Resource
    private ToolBlockRecordDao toolBlockRecordDao;

    @Override
    public TrajectoryVo conversation(String conversationId) {
        TrajectoryVo vo = new TrajectoryVo();
        vo.setConversationId(conversationId);
        if (Strings.isBlank(conversationId)) {
            return vo;
        }

        TaskInstanceSelectDto taskQuery = new TaskInstanceSelectDto();
        taskQuery.setConversationId(conversationId);
        taskQuery.setSorts(List.of(SORT_CREATE_TIME_ASC));
        taskQuery.setDefaultIdDescSortEnabled(false);
        List<TaskInstancePo> tasks = taskInstanceDao.selectSummaryList(taskQuery);
        if (Objects.isNull(tasks) || tasks.isEmpty()) {
            return vo;
        }

        List<String> taskIds = new ArrayList<>(tasks.size());
        Map<String, TrajectoryVo.Task> taskMap = new LinkedHashMap<>();
        for (TaskInstancePo po : tasks) {
            TrajectoryVo.Task task = toTask(po);
            taskIds.add(po.getTaskId());
            taskMap.put(po.getTaskId(), task);
            vo.getTasks().add(task);
        }

        for (TaskGraphNodePo node : taskGraphNodeDao.selectByTaskIds(taskIds)) {
            TrajectoryVo.Task task = taskMap.get(node.getTaskId());
            if (Objects.isNull(task)) {
                continue;
            }
            task.getNodes().add(toNode(node));
        }

        for (ToolCallRecordPo call : toolCallRecordDao.selectByTaskIds(taskIds)) {
            vo.getCalls().add(toCall(call));
        }

        for (ToolBlockRecordPo block : toolBlockRecordDao.selectByTaskIds(taskIds)) {
            vo.getBlocks().add(toBlock(block));
        }

        return vo;
    }

    private static TrajectoryVo.Task toTask(TaskInstancePo po) {
        TrajectoryVo.Task task = new TrajectoryVo.Task();
        task.setTaskId(po.getTaskId());
        task.setAgentId(po.getAgentId());
        task.setStatus(po.getStatus());
        task.setTraceId(po.getTraceId());
        task.setStartedTime(po.getStartedTime());
        task.setFinishedTime(po.getFinishedTime());
        return task;
    }

    private static TrajectoryVo.Node toNode(TaskGraphNodePo po) {
        TrajectoryVo.Node node = new TrajectoryVo.Node();
        node.setNodeId(po.getNodeId());
        node.setTitle(po.getTitle());
        node.setSkillKey(po.getSkillKey());
        node.setStatus(po.getStatus());
        node.setErrorCode(po.getErrorCode());
        return node;
    }

    private static TrajectoryVo.Call toCall(ToolCallRecordPo po) {
        TrajectoryVo.Call call = new TrajectoryVo.Call();
        call.setCallId(po.getCallId());
        call.setTaskId(po.getTaskId());
        call.setNodeId(po.getNodeId());
        call.setRound(po.getRound());
        call.setToolName(po.getToolName());
        call.setSideEffect(po.getSideEffect());
        call.setRequest(po.getRequest());
        call.setCallStatus(po.getCallStatus());
        call.setBusinessOk(po.getBusinessOk());
        call.setEmptyResult(po.getEmptyResult());
        call.setErrorCode(po.getErrorCode());
        call.setErrorMessage(po.getErrorMessage());
        call.setDurationMs(po.getDurationMs());
        call.setTraceId(po.getTraceId());
        call.setCreateTime(po.getCreateTime());
        return call;
    }

    private static TrajectoryVo.Block toBlock(ToolBlockRecordPo po) {
        TrajectoryVo.Block block = new TrajectoryVo.Block();
        block.setCallId(po.getCallId());
        block.setTaskId(po.getTaskId());
        block.setNodeId(po.getNodeId());
        block.setRound(po.getRound());
        block.setSkillKey(po.getSkillKey());
        block.setToolName(po.getToolName());
        block.setBlockedBy(po.getBlockedBy());
        block.setErrorCode(po.getErrorCode());
        block.setReasonCode(po.getReasonCode());
        block.setMessage(po.getMessage());
        block.setArgs(po.getArgs());
        block.setTraceId(po.getTraceId());
        block.setCreateTime(po.getCreateTime());
        return block;
    }
}
