package com.g2rain.agent.runtime.controller;

import com.g2rain.agent.runtime.api.TrajectoryApi;
import com.g2rain.agent.runtime.service.TrajectoryService;
import com.g2rain.agent.runtime.vo.TrajectoryVo;
import com.g2rain.common.model.Result;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话执行轨迹控制器。
 *
 * @author alpha
 * @since 2026/11/23
 */
@RestController
public class TrajectoryController implements TrajectoryApi {

    @Resource(name = "trajectoryServiceImpl")
    private TrajectoryService trajectoryService;

    @Override
    public Result<TrajectoryVo> conversation(String conversationId) {
        return Result.success(trajectoryService.conversation(conversationId));
    }
}
