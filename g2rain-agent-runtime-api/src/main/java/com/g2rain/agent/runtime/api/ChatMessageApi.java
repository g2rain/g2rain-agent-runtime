package com.g2rain.agent.runtime.api;

import com.g2rain.common.model.PageData;
import com.g2rain.common.model.PageSelectListDto;
import com.g2rain.common.model.Result;
import com.g2rain.agent.runtime.dto.ChatMessageSelectDto;
import com.g2rain.agent.runtime.vo.ChatMessageVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;


/**
 * 聊天消息表API接口
 * 表名: chat_message
 *
 * @author G2rain Generator
 */
@Tag(name = "聊天消息表", description = "聊天消息表相关接口")
public interface ChatMessageApi {

    /**
     * 根据条件查询列表
     *
     * @param selectDto 查询条件 DTO
     * @return 数据列表
     */
    @GetMapping("/list")
    @Operation(summary = "查询聊天消息表列表", description = "根据查询条件返回聊天消息表列表")
    Result<List<ChatMessageVo>> selectList(ChatMessageSelectDto selectDto);

    /**
     * 根据条件分页查询
     *
     * @param selectDto 查询条件DTO（包含分页参数）
     * @return 分页数据
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询聊天消息表列表", description = "分页查询聊天消息表列表")
    Result<PageData<ChatMessageVo>> selectPage(PageSelectListDto<ChatMessageSelectDto> selectDto);
}