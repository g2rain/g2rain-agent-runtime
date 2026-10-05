package com.g2rain.agent.runtime.service;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.agent.runtime.dto.TaskInstanceDto;
import com.g2rain.agent.runtime.dto.TaskInstanceSelectDto;
import com.g2rain.agent.runtime.vo.TaskInstanceVo;

import java.util.List;

/**
 * 任务实例表服务接口
 * 表名: task_instance
 *
 * @author G2rain Generator
 */
public interface TaskInstanceService {

    /**
     * 根据条件查询列表
     *
     * @param selectDto 查询条件 DTO
     * @return VO 对象列表
     */
    List<TaskInstanceVo> selectList(TaskInstanceSelectDto selectDto);

    /**
     * 根据条件分页查询
     *
     * @param selectDto 查询条件DTO（包含分页参数）
     * @return 分页 VO 数据
     */
    PageData<TaskInstanceVo> selectPage(PageSelectListDto<TaskInstanceSelectDto> selectDto);

    /**
     * 新增或更新数据
     *
     * @param dto 数据传输对象
     * @return 操作结果（影响行数）
     */
    Long save(TaskInstanceDto dto);

    /**
     * 轮末 CAS：推进 state_version，并写任务冷字段。
     *
     * @param dto 须带 taskId、stateVersion 及本轮列
     * @return 影响行数；0 表示冲突或行不存在
     */
    int advanceStateVersion(TaskInstanceDto dto);

    /**
     * 根据 ID 删除数据
     *
     * @param id 主键 ID
     * @return 操作结果（影响行数）
     */
    int delete(Long id);
}