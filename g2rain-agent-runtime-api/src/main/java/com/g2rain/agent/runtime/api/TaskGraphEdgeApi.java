package com.g2rain.agent.runtime.api;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.dto.TaskGraphEdgeSelectDto;
import com.g2rain.agent.runtime.vo.TaskGraphEdgeVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;


/**
 * 任务图依赖边表API接口
 * 表名: task_graph_edge
 *
 * @author G2rain Generator
 */
@Tag(name = "任务图依赖边表", description = "任务图依赖边表相关接口")
public interface TaskGraphEdgeApi {

    /**
     * 根据条件查询列表
     *
     * @param selectDto 查询条件 DTO
     * @return 数据列表
     */
    @GetMapping("/list")
    @Operation(summary = "查询任务图依赖边表列表", description = "根据查询条件返回任务图依赖边表列表")
    Result<List<TaskGraphEdgeVo>> selectList(TaskGraphEdgeSelectDto selectDto);

    /**
     * 根据条件分页查询
     *
     * @param selectDto 查询条件DTO（包含分页参数）
     * @return 分页数据
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询任务图依赖边表列表", description = "分页查询任务图依赖边表列表")
    Result<PageData<TaskGraphEdgeVo>> selectPage(PageSelectListDto<TaskGraphEdgeSelectDto> selectDto);
}