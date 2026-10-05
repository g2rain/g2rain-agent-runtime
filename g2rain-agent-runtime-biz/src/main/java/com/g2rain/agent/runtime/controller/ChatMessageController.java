package com.g2rain.agent.runtime.controller;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.api.ChatMessageApi;
import com.g2rain.agent.runtime.dto.ChatMessageDto;
import com.g2rain.agent.runtime.dto.ChatMessageSelectDto;
import com.g2rain.agent.runtime.service.ChatMessageService;
import com.g2rain.agent.runtime.vo.ChatMessageVo;
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
 * 聊天消息表控制器
 * 表名: chat_message
 *
 * @author G2rain Generator
 */
@RestController
@RequestMapping("/chat_message")
public class ChatMessageController implements ChatMessageApi {

    @Resource(name = "chatMessageServiceImpl")
    private ChatMessageService chatMessageService;

    @Override
    public Result<List<ChatMessageVo>> selectList(ChatMessageSelectDto selectDto) {
        return Result.success(chatMessageService.selectList(selectDto));
    }

    @Override
    public Result<PageData<ChatMessageVo>> selectPage(PageSelectListDto<ChatMessageSelectDto> selectDto) {
        return Result.successPage(chatMessageService.selectPage(selectDto));
    }

    @PostMapping("/save")
    @Operation(summary = "新增或更新聊天消息表信息", description = "新增或更新聊天消息表基础信息")
    public Result<Long> save(@RequestBody ChatMessageDto dto) {
        return Result.success(chatMessageService.save(dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除聊天消息表记录", description = "根据主键删除聊天消息表记录")
    public Result<Integer> delete(@Parameter(description = "聊天消息表标识") @PathVariable Long id) {
        return Result.success(chatMessageService.delete(id));
    }
}