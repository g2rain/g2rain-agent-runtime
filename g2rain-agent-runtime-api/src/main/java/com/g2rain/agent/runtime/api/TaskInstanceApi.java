package com.g2rain.agent.runtime.api;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.dto.TaskInstanceSelectDto;
import com.g2rain.agent.runtime.vo.TaskInstanceVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;


/**
 * 任务实例表API接口
 * 表名: task_instance
 *
 * @author G2rain Generator
 */
@Tag(name = "任务实例表", description = "任务实例表相关接口")
public interface TaskInstanceApi {

    /**
     * 根据条件查询列表
     *
     * @param selectDto 查询条件 DTO
     * @return 数据列表
     */
    @GetMapping("/list")
    @Operation(summary = "查询任务实例表列表", description = "根据查询条件返回任务实例表列表")
    Result<List<TaskInstanceVo>> selectList(TaskInstanceSelectDto selectDto);

    /**
     * 根据条件分页查询
     *
     * @param selectDto 查询条件DTO（包含分页参数）
     * @return 分页数据
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询任务实例表列表", description = "分页查询任务实例表列表")
    Result<PageData<TaskInstanceVo>> selectPage(PageSelectListDto<TaskInstanceSelectDto> selectDto);
}