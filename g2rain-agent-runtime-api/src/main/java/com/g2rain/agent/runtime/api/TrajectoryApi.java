package com.g2rain.agent.runtime.api;

import com.g2rain.agent.runtime.vo.TrajectoryVo;
import com.g2rain.common.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 会话执行轨迹只读接口，供可观测与调试按会话读取任务、节点、调用与拦截。
 *
 * @author alpha
 * @since 2026/11/23
 */
@Tag(name = "会话执行轨迹", description = "按会话读取执行轨迹")
public interface TrajectoryApi {

    /**
     * 按会话读取执行轨迹；会话不存在或不属于当前机构时返回空结构。
     *
     * @param conversationId 会话标识
     * @return 轨迹；不含工具响应原文
     */
    @GetMapping("/trajectory")
    @Operation(summary = "按会话读取执行轨迹", description = "返回该会话下的任务、节点、工具调用与拦截，供可观测与调试")
    Result<TrajectoryVo> conversation(
        @Parameter(description = "会话标识", required = true) @RequestParam("conversationId") String conversationId);
}
