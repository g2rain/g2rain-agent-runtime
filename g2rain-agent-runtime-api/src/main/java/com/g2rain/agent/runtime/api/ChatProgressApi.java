package com.g2rain.agent.runtime.api;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.dto.ChatProgressSelectDto;
import com.g2rain.agent.runtime.vo.ChatProgressVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;


/**
 * 执行过程步骤表API接口
 * 表名: chat_progress
 *
 * @author G2rain Generator
 */
@Tag(name = "执行过程步骤表", description = "执行过程步骤表相关接口")
public interface ChatProgressApi {

    /**
     * 根据条件查询列表
     *
     * @param selectDto 查询条件 DTO
     * @return 数据列表
     */
    @GetMapping("/list")
    @Operation(summary = "查询执行过程步骤表列表", description = "根据查询条件返回执行过程步骤表列表")
    Result<List<ChatProgressVo>> selectList(ChatProgressSelectDto selectDto);

    /**
     * 根据条件分页查询
     *
     * @param selectDto 查询条件DTO（包含分页参数）
     * @return 分页数据
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询执行过程步骤表列表", description = "分页查询执行过程步骤表列表")
    Result<PageData<ChatProgressVo>> selectPage(PageSelectListDto<ChatProgressSelectDto> selectDto);
}