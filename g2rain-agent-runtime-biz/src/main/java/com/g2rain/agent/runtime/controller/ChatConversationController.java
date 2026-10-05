package com.g2rain.agent.runtime.controller;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.api.ChatConversationApi;
import com.g2rain.agent.runtime.dto.ChatConversationDto;
import com.g2rain.agent.runtime.dto.ChatConversationSelectDto;
import com.g2rain.agent.runtime.service.ChatConversationService;
import com.g2rain.agent.runtime.vo.ChatConversationVo;
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
 * 聊天会话表控制器
 * 表名: chat_conversation
 *
 * @author G2rain Generator
 */
@RestController
@RequestMapping("/chat_conversation")
public class ChatConversationController implements ChatConversationApi {

    @Resource(name = "chatConversationServiceImpl")
    private ChatConversationService chatConversationService;

    @Override
    public Result<List<ChatConversationVo>> selectList(ChatConversationSelectDto selectDto) {
        return Result.success(chatConversationService.selectList(selectDto));
    }

    @Override
    public Result<PageData<ChatConversationVo>> selectPage(PageSelectListDto<ChatConversationSelectDto> selectDto) {
        return Result.successPage(chatConversationService.selectPage(selectDto));
    }

    @PostMapping("/save")
    @Operation(summary = "新增或更新聊天会话表信息", description = "新增或更新聊天会话表基础信息")
    public Result<Long> save(@RequestBody ChatConversationDto dto) {
        return Result.success(chatConversationService.save(dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除聊天会话表记录", description = "根据主键删除聊天会话表记录")
    public Result<Integer> delete(@Parameter(description = "聊天会话表标识") @PathVariable Long id) {
        return Result.success(chatConversationService.delete(id));
    }
}