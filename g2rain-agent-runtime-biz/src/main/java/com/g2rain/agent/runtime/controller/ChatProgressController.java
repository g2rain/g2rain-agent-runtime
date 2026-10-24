package com.g2rain.agent.runtime.controller;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.api.ChatProgressApi;
import com.g2rain.agent.runtime.dto.ChatProgressDto;
import com.g2rain.agent.runtime.dto.ChatProgressSelectDto;
import com.g2rain.agent.runtime.service.ChatProgressService;
import com.g2rain.agent.runtime.vo.ChatProgressVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 执行过程步骤表控制器
 * 表名: chat_progress
 *
 * @author G2rain Generator
 */
@RestController
@RequestMapping("/chat_progress")
public class ChatProgressController implements ChatProgressApi {

    @Resource(name = "chatProgressServiceImpl")
    private ChatProgressService chatProgressService;

    @Override
    public Result<List<ChatProgressVo>> selectList(ChatProgressSelectDto selectDto) {
        return Result.success(chatProgressService.selectList(selectDto));
    }

    @Override
    public Result<PageData<ChatProgressVo>> selectPage(PageSelectListDto<ChatProgressSelectDto> selectDto) {
        return Result.successPage(chatProgressService.selectPage(selectDto));
    }

    @PostMapping("/save")
    @Operation(summary = "新增或更新执行过程步骤表信息", description = "新增或更新执行过程步骤表基础信息")
    public Result<Long> save(@RequestBody ChatProgressDto dto) {
        return Result.success(chatProgressService.save(dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除执行过程步骤表记录", description = "根据主键删除执行过程步骤表记录")
    public Result<Integer> delete(@Parameter(description = "执行过程步骤表标识") @PathVariable Long id) {
        return Result.success(chatProgressService.delete(id));
    }
}