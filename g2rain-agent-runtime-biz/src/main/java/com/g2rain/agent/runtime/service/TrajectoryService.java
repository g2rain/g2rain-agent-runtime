package com.g2rain.agent.runtime.service;

import com.g2rain.agent.runtime.vo.TrajectoryVo;

/**
 * 会话执行轨迹查询。
 *
 * @author alpha
 * @since 2026/11/23
 */
public interface TrajectoryService {

    /**
     * 按会话组装执行轨迹；会话不存在或不属于当前机构时返回空列表结构。
     *
     * @param conversationId 会话标识
     * @return 轨迹
     */
    TrajectoryVo conversation(String conversationId);
}
